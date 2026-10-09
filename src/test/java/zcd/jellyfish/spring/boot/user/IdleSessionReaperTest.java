package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.di.JellyfishAssembler;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.spring.boot.JellyfishLifecycle;
import zcd.jellyfish.spring.boot.JellyfishProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IdleSessionReaper} 的集成测试：真内核 + 真会话，验的是「该收的收、不该收的不收」。
 * <p>
 * <b>内核由本用例自己装配</b>（{@link JellyfishAssembler#create}），不走 Spring：starter 默认不把内核门面
 * 交给容器（理由见 {@code JellyfishKernel}），而本用例要直接建会话、塞在途回合——这正是「拿内核能力」的
 * 那条正路：自己装一套，自己管它的生命周期。
 * <p>
 * 三道判断各有一个用例，其中「不该收」的两道都是真实故障的防线：
 * <ul>
 *     <li><b>跳过有在途回合的会话</b>——长 LLM 调用在第一条新消息落库之前不刷新时间戳，
 *     只看时间戳会把正在跑的会话关掉，现场表现是「模型还在回答，会话却没了」；</li>
 *     <li><b>跳过没有归属者的会话</b>——那些不是本层建的，关掉超出了本层的职责。</li>
 * </ul>
 *
 * @author zcd
 */
@DisplayName("IdleSessionReaper 闲置会话回收")
class IdleSessionReaperTest {

    /** 临时目录，给插件扫描用（不扫使用者的真实插件目录）。 */
    @TempDir
    Path tempDir;

    /** 本用例自己装配的内核。 */
    private JellyfishRuntime runtime;

    /** 归属存储：用真实现，验的是「回收时归属一起解绑」。 */
    private SessionOwnerStore owners;

    /** 被测回收器：闲置门槛 1 毫秒，扫描间隔 1 小时（只有手工调 sweep 才会扫）。 */
    private IdleSessionReaper reaper;

    @BeforeEach
    void setUp() {
        JellyfishProperties properties = new JellyfishProperties();
        // 四份配置路径清空、插件目录指向临时目录：本用例不需要真实配置，也不该读使用者的家目录
        properties.getModels().setGlobalPath("");
        properties.getModels().setProjectPath("");
        properties.getAgents().setGlobalPath("");
        properties.getAgents().setProjectPath("");
        properties.getSettings().setGlobalPath("");
        properties.getSettings().setProjectPath("");
        properties.getPlugins().setRoots(java.util.Collections.singletonList(tempDir.toString()));
        runtime = JellyfishAssembler.create(properties.toAppConfig());

        owners = new InMemorySessionOwnerStore();
        reaper = reaperWith(Duration.ofMillis(1L));
    }

    /**
     * 造一个回收器，只改闲置门槛（扫描间隔 1 小时，只有手工调 sweep 才会扫）。
     *
     * @param idleTimeout 闲置门槛
     * @return 被测回收器，保证非 {@code null}
     */
    private IdleSessionReaper reaperWith(Duration idleTimeout) {
        JellyfishUserProperties userProperties = new JellyfishUserProperties();
        userProperties.setIdleTimeout(idleTimeout);
        userProperties.setSweepInterval(Duration.ofHours(1L));
        return new IdleSessionReaper(runtime.sessionManager(), owners, runtime.turnRegistry(),
                userProperties);
    }

    @Test
    @DisplayName("超过闲置门槛的会话被回收，归属也一起解绑")
    void sweep_should_close_session_when_it_is_idle() {
        // Given：一个有归属者的会话，且已经闲置过去
        String sessionId = ownedSession("alice");
        awaitIdleWindow();

        // When
        int closed = reaper.sweep();

        // Then：会话没了，归属也一起解绑——留下归属会变成谁也访问不到的记录
        assertEquals(1, closed);
        assertFalse(runtime.sessionManager().exists(sessionId));
        assertFalse(owners.owns("alice", sessionId), "归属必须一起解绑，否则会留下谁也访问不到的记录");
    }

    @Test
    @DisplayName("有在途回合的会话不被回收（长调用不刷新时间戳，只看时间会误杀）")
    void sweep_should_skip_session_when_a_turn_is_running() {
        // Given：一个有归属者的会话，里面有一个「永不结束」的回合
        String sessionId = ownedSession("alice");
        awaitIdleWindow();
        // 走真的 TurnRegistry，而不是 mock 它：这里要验的正是「回收器确实去问了在途回合表」。
        // 绑定要带槽位——槽位是「这次登记还算不算数」的唯一依据（见 TurnRegistry.bind）
        TurnRegistry.Slot slot = runtime.turnRegistry().acquire(sessionId);
        runtime.turnRegistry().bind(slot, new NeverDoneTurn());
        assertTrue(runtime.turnRegistry().isRunning(sessionId), "前置条件：会话应当在跑");

        // When
        int closed = reaper.sweep();

        // Then
        assertEquals(0, closed);
        assertTrue(runtime.sessionManager().exists(sessionId), "正在跑的会话必须留着");
    }

    @Test
    @DisplayName("没有归属者的会话不被回收（不是本层建的，不越权处置）")
    void sweep_should_skip_session_when_no_owner_is_recorded() {
        // Given：直接建一个会话，不记归属——模拟「有人绕过本层建了会话」
        Session orphan = runtime.sessionManager().create(null, null, null);
        awaitIdleWindow();

        // When
        int closed = reaper.sweep();

        // Then
        assertEquals(0, closed);
        assertTrue(runtime.sessionManager().exists(orphan.getSessionId()), "没有归属者的会话不该被本层关掉");
    }

    @Test
    @DisplayName("只回收闲置的那些，刚活动过的会话不受影响")
    void sweep_should_only_close_idle_sessions_when_mixed() {
        // Given：一个闲置会话 + 一个刚建出来的会话
        String idle = ownedSession("alice");
        awaitIdleWindow();
        String fresh = ownedSession("alice");

        // When
        int closed = reaper.sweep();

        // Then
        assertEquals(1, closed);
        assertFalse(runtime.sessionManager().exists(idle));
        assertTrue(runtime.sessionManager().exists(fresh), "刚活动过的会话不该被回收");
    }

    @Test
    @DisplayName("启动与停止都幂等，且排在内核之后启动、之前停止")
    void lifecycle_should_be_idempotent_when_started_and_stopped_twice() {
        // Then
        assertTrue(reaper.getPhase() > JellyfishLifecycle.PHASE, "回收器必须排在内核之后启动、之前停止");

        reaper.start();
        assertTrue(reaper.isRunning());
        reaper.start();
        assertTrue(reaper.isRunning());
        reaper.stop();
        // 内核实现在重复 stop 时也要安全：SmartLifecycle 的调用方（容器）不保证只调一次
        reaper.stop();
        assertFalse(reaper.isRunning());
    }

    @Test
    @DisplayName("idle-timeout 配成 0 表示不回收：不关会话、不解绑归属（配置的人以为自己在写「不限」）")
    void sweep_should_reap_nothing_when_idle_timeout_is_zero() {
        // Given：门槛 0。折算成一个很小的门槛就会变成「每轮扫描清空所有空闲会话」，
        // 而客户端看到的只是自己的 sessionId 突然 404
        IdleSessionReaper disabled = reaperWith(Duration.ZERO);
        String sessionId = ownedSession("alice");
        awaitIdleWindow();

        // When
        int closed = disabled.sweep();

        // Then
        assertEquals(0, closed);
        assertTrue(runtime.sessionManager().exists(sessionId), "「不回收」不能变成「把空闲会话全关掉」");
        assertTrue(owners.owns("alice", sessionId), "归属也不该被解绑");
    }

    @Test
    @DisplayName("idle-timeout 配成负数或缺失同样是「不回收」，且回收器根本不启动")
    void reaper_should_not_start_when_idle_timeout_is_not_positive() {
        // Given
        for (Duration configured : Arrays.asList(Duration.ofSeconds(-1L), null)) {
            IdleSessionReaper disabled = reaperWith(configured);
            String sessionId = ownedSession("alice");
            awaitIdleWindow();

            // When
            disabled.start();

            // Then：连调度线程都不排——它什么都不会做，而空闲线程也是一种占用
            assertFalse(disabled.isRunning(), "「不回收」时回收器不该在运行：" + configured);
            assertEquals(0, disabled.sweep(), "手工调 sweep 也一次不关：" + configured);
            assertTrue(runtime.sessionManager().exists(sessionId), "会话必须留着：" + configured);
        }
    }

    /**
     * 建一个会话并记下归属。
     *
     * @param userId 归属者标识
     * @return 新会话标识
     */
    private String ownedSession(String userId) {
        String sessionId = runtime.sessionManager().create(null, null, null).getSessionId();
        owners.bind(userId, sessionId);
        return sessionId;
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
     * 刻意走真的 {@code TurnRegistry} 而不是 mock 它：这里要验的正是「回收器确实去问了在途回合表」——
     * 把表换成假的，测的就只是我自己写的那一行 if。
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
