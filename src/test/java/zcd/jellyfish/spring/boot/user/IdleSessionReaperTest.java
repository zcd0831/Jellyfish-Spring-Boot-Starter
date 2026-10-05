package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.spring.boot.JellyfishAutoConfiguration;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IdleSessionReaper} 的集成测试：真内核 + 真会话，验的是「该收的收、不该收的不收」。
 * <p>
 * 三道判断各有一个用例，其中「不该收」的两道都是真实故障的防线：
 * <ul>
 *     <li><b>跳过有在途回合的会话</b>——长 LLM 调用在第一条新消息落库之前不刷新时间戳，
 *     只看时间戳会把正在跑的会话关掉，现场表现是「模型还在回答，会话却没了」；</li>
 *     <li><b>跳过没有归属者的会话</b>——那些不是本层建的，关掉超出了本层的职责。</li>
 * </ul>
 * 在途回合那一条用 mock 的 {@code TurnRegistry}：真造一个「跑到一半且长时间不结束」的回合需要配一个
 * 模型，而这里要验的只是「reaper 会先问一句」这一点。
 *
 * @author zcd
 */
@DisplayName("IdleSessionReaper 闲置会话回收")
class IdleSessionReaperTest {

    /** 临时目录，给插件扫描用。 */
    @TempDir
    Path tempDir;

    /**
     * 构造测试载体：闲置门槛设成 1 毫秒，扫描间隔设成 1 小时（只有手工调 sweep 才会扫）。
     *
     * @return 测试载体
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JellyfishAutoConfiguration.class,
                        JellyfishUserAutoConfiguration.class))
                .withPropertyValues(
                        "jellyfish.models.global-path=",
                        "jellyfish.models.project-path=",
                        "jellyfish.agents.global-path=",
                        "jellyfish.agents.project-path=",
                        "jellyfish.settings.global-path=",
                        "jellyfish.settings.project-path=",
                        "jellyfish.plugins.roots=" + tempDir,
                        "jellyfish.lifecycle.bootstrap-on-start=true",
                        "jellyfish.user.idle-timeout=1ms",
                        "jellyfish.user.sweep-interval=1h");
    }

    @Test
    @DisplayName("超过闲置门槛的会话被回收，归属也一起解绑")
    void sweep_should_close_session_when_it_is_idle() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            SessionOwnerStore owners = context.getBean(SessionOwnerStore.class);
            String sessionId = facade.createSession("alice");
            awaitIdleWindow();

            int closed = context.getBean(IdleSessionReaper.class).sweep();

            assertEquals(1, closed);
            assertFalse(context.getBean(JellyfishRuntime.class).sessionManager().exists(sessionId));
            assertFalse(owners.owns("alice", sessionId), "归属必须一起解绑，否则会留下谁也访问不到的记录");
            assertEquals(0, facade.listSessions("alice").size());
        });
    }

    @Test
    @DisplayName("有在途回合的会话不被回收（长调用不刷新时间戳，只看时间会误杀）")
    void sweep_should_skip_session_when_a_turn_is_running() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            JellyfishRuntime runtime = context.getBean(JellyfishRuntime.class);
            String sessionId = facade.createSession("alice");
            awaitIdleWindow();
            // 用一个「永不结束」的回合占住这个会话：走真的 TurnRegistry，而不是 mock 它——
            // 它是 final 类，而更重要的是这里要验的正是「reaper 确实去问了在途回合表」
            runtime.turnRegistry().bind(sessionId, new NeverDoneTurn());
            assertTrue(runtime.turnRegistry().isRunning(sessionId), "前置条件：会话应当在跑");

            int closed = context.getBean(IdleSessionReaper.class).sweep();

            assertEquals(0, closed);
            assertTrue(runtime.sessionManager().exists(sessionId), "正在跑的会话必须留着");
        });
    }

    @Test
    @DisplayName("没有归属者的会话不被回收（不是本层建的，不越权处置）")
    void sweep_should_skip_session_when_no_owner_is_recorded() {
        // When / Then
        runner().run(context -> {
            JellyfishRuntime runtime = context.getBean(JellyfishRuntime.class);
            // 绕过门面直接建会话：模拟「有人直接用内核 API 建了会话」，那种会话没有归属记录
            String orphan = runtime.sessionManager().create(null, null, null).getSessionId();
            awaitIdleWindow();

            int closed = context.getBean(IdleSessionReaper.class).sweep();

            assertEquals(0, closed);
            assertTrue(runtime.sessionManager().exists(orphan), "没有归属者的会话不该被本层关掉");
        });
    }

    @Test
    @DisplayName("只回收闲置的那些，正在用的会话不受影响")
    void sweep_should_only_close_idle_sessions_when_mixed() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String idle = facade.createSession("alice");
            awaitIdleWindow();
            // 建一个新会话并读一次，让它「刚刚活动过」
            String fresh = facade.createSession("alice");

            int closed = context.getBean(IdleSessionReaper.class).sweep();

            assertEquals(1, closed);
            JellyfishRuntime runtime = context.getBean(JellyfishRuntime.class);
            assertFalse(runtime.sessionManager().exists(idle));
            assertTrue(runtime.sessionManager().exists(fresh), "刚活动过的会话不该被回收");
        });
    }

    @Test
    @DisplayName("启动与停止都幂等，停止后不再有定时任务")
    void lifecycle_should_be_idempotent_when_started_and_stopped_twice() {
        // When / Then
        runner().run(context -> {
            IdleSessionReaper reaper = context.getBean(IdleSessionReaper.class);
            assertTrue(reaper.isRunning());
            assertTrue(reaper.getPhase() > zcd.jellyfish.spring.boot.JellyfishLifecycle.PHASE,
                    "回收器必须排在内核之后启动、之前停止");

            reaper.start();
            assertTrue(reaper.isRunning());
            reaper.stop();
            reaper.stop();
            assertFalse(reaper.isRunning());
        });
    }

    /**
     * 等到超过闲置门槛（1 毫秒），留出足够余量避免时序抖动。
     */
    private static void awaitIdleWindow() {
        try {
            Thread.sleep(30L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一个「永不结束」的回合，用来占住某个会话的在途状态。
     * <p>
     * 刻意走真的 {@code TurnRegistry} 而不是 mock 它：它是 final 类，但更重要的是这里要验的正是
     * 「回收器确实去问了在途回合表」——把表换成假的，测的就只是我自己写的那一行 if。
     *
     * @author zcd
     */
    private static final class NeverDoneTurn implements ReActTurn {

        @Override
        public String getTurnId() {
            return "never-done-turn";
        }

        @Override
        public void cancel() {
            // 刻意不响应取消：模拟「长 LLM 调用还没结束」这一段
        }

        @Override
        public ReActResult await() {
            return ReActResult.cancelled("never-done", 0);
        }

        @Override
        public boolean isDone() {
            return false;
        }
    }
}
