package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.spring.boot.JellyfishAutoConfiguration;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishUserFacade} 的集成测试：用真内核、真装配，验的是「越权与配额这两道闸门真的在起作用」。
 * <p>
 * <b>为什么必须用真内核</b>：本类的全部价值都在「与内核既有语义的咬合」上——会话表怎么建、
 * 事件怎么推、命令怎么派发。把这些 mock 掉之后，测的就只剩我自己写的那几行 if。
 * <p>
 * 每个用例都把配置路径清空、插件目录指向临时目录：单测不碰使用者的 {@code ~/.jellyfish}，
 * 也不该扫到他机器上的真实插件。
 *
 * @author zcd
 */
@DisplayName("JellyfishUserFacade 多用户门面")
class JellyfishUserFacadeTest {

    /** 临时目录，给插件扫描用。 */
    @TempDir
    Path tempDir;

    /**
     * 构造测试载体。
     *
     * @param extraProperties 额外的属性（覆盖缺省）
     * @return 测试载体
     */
    private ApplicationContextRunner runner(String... extraProperties) {
        String[] base = {
                "jellyfish.models.global-path=",
                "jellyfish.models.project-path=",
                "jellyfish.agents.global-path=",
                "jellyfish.agents.project-path=",
                "jellyfish.settings.global-path=",
                "jellyfish.settings.project-path=",
                "jellyfish.plugins.roots=" + tempDir,
                // 必须真启动内核：系统命令（/status、/usage…）是在 AgentHarness.bootstrap() 里注册的，
                // 不起内核的话命令域是空的——那样测出来的「命令能跑」是假的
                "jellyfish.lifecycle.bootstrap-on-start=true",
                "jellyfish.user.reaper-enabled=false"
        };
        String[] all = new String[base.length + extraProperties.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extraProperties, 0, all, base.length, extraProperties.length);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JellyfishAutoConfiguration.class,
                        JellyfishUserAutoConfiguration.class))
                .withPropertyValues(all);
    }

    @Test
    @DisplayName("建会话会记下归属，且只在归属者自己的列表里出现")
    void createSession_should_bind_owner_when_called() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            assertNotNull(sessionId);
            List<SessionSummary> aliceSessions = facade.listSessions("alice");
            assertEquals(1, aliceSessions.size());
            assertEquals(sessionId, aliceSessions.get(0).getSessionId());
            assertEquals(0, facade.listSessions("bob").size(), "别人不该看到这个会话");
        });
    }

    @Test
    @DisplayName("跨用户取快照被拒，且答复与「会话不存在」完全一样")
    void snapshot_should_be_denied_when_requester_is_not_the_owner() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            SessionAccessDeniedException crossUser = assertThrows(SessionAccessDeniedException.class,
                    () -> facade.snapshot("bob", sessionId));
            SessionAccessDeniedException unknown = assertThrows(SessionAccessDeniedException.class,
                    () -> facade.snapshot("bob", "no-such-session"));

            // 两条消息只差在「请求的是哪个 id」，不含任何关于归属的线索——
            // 一旦它们能区分「存在但不是你的」与「不存在」，就等于给了攻击者一个枚举会话 id 的工具
            assertEquals("会话不存在或无权访问：" + sessionId, crossUser.getMessage());
            assertEquals("会话不存在或无权访问：no-such-session", unknown.getMessage());
            // 而异常里仍然带着请求者，供服务端日志定位（不该回显给调用方）
            assertEquals("bob", crossUser.getUserId());
        });
    }

    @Test
    @DisplayName("按会话操作的一整组方法都会拒绝非归属者")
    void session_operations_should_all_reject_when_requester_is_not_the_owner() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            assertThrows(SessionAccessDeniedException.class, () -> facade.closeSession("bob", sessionId));
            assertThrows(SessionAccessDeniedException.class, () -> facade.deleteSession("bob", sessionId));
            assertThrows(SessionAccessDeniedException.class, () -> facade.cancel("bob", sessionId));
            assertThrows(SessionAccessDeniedException.class, () -> facade.isTurnRunning("bob", sessionId));
            assertThrows(SessionAccessDeniedException.class,
                    () -> facade.pendingApprovals("bob", sessionId));
            assertThrows(SessionAccessDeniedException.class,
                    () -> facade.approve("bob", sessionId, "req", true));
            assertThrows(SessionAccessDeniedException.class,
                    () -> facade.executeCommand("bob", sessionId, "/status"));
        });
    }

    @Test
    @DisplayName("归属者自己操作一切正常")
    void session_operations_should_succeed_when_requester_is_the_owner() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            assertNotNull(facade.snapshot("alice", sessionId));
            assertFalse(facade.isTurnRunning("alice", sessionId), "还没起回合");
            assertFalse(facade.cancel("alice", sessionId), "没有在跑的回合可取消");
            assertTrue(facade.pendingApprovals("alice", sessionId).isEmpty());
            assertFalse(facade.approve("alice", sessionId, "req", true), "没有待审批项");
            assertFalse(facade.executeCommand("alice", sessionId, "/status").isError());
        });
    }

    @Test
    @DisplayName("关闭会话会解除归属，会话从列表里消失")
    void closeSession_should_unbind_when_called() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            facade.closeSession("alice", sessionId);

            assertEquals(0, facade.listSessions("alice").size());
            assertFalse(context.getBean(SessionOwnerStore.class).owns("alice", sessionId));
            // 关闭之后连归属者也不能再操作它（会话已不在会话表里）
            assertThrows(SessionAccessDeniedException.class, () -> facade.snapshot("alice", sessionId));
        });
    }

    @Test
    @DisplayName("删除会话会解除归属")
    void deleteSession_should_unbind_when_called() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            facade.deleteSession("alice", sessionId);

            assertEquals(0, facade.listSessions("alice").size());
            assertFalse(context.getBean(SessionOwnerStore.class).owns("alice", sessionId));
        });
    }

    @Test
    @DisplayName("会话数到上限时拒绝新建，删掉一个之后又能建")
    void createSession_should_reject_when_session_quota_reached() {
        // When / Then
        runner("jellyfish.user.max-sessions-per-user=2").run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String first = facade.createSession("alice");
            facade.createSession("alice");

            UserQuotaExceededException thrown = assertThrows(UserQuotaExceededException.class,
                    () -> facade.createSession("alice"));
            assertEquals("alice", thrown.getUserId());

            // 别的用户不受影响
            assertNotNull(facade.createSession("bob"));

            facade.deleteSession("alice", first);
            assertNotNull(facade.createSession("alice"), "腾出位置后应当又能建");
        });
    }

    @Test
    @DisplayName("列表会顺手清掉指向已消失会话的归属记录")
    void listSessions_should_cleanup_stale_binding_when_session_is_gone() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            SessionOwnerStore owners = context.getBean(SessionOwnerStore.class);
            // 直接写一条指向不存在会话的归属：模拟「会话因别的原因消失」，
            // 例如持久化插件没恢复成功、或者有人绕过门面直接调内核 API 关掉了
            owners.bind("alice", "ghost-session");

            assertEquals(0, facade.listSessions("alice").size());
            assertFalse(owners.owns("alice", "ghost-session"), "读取路径应当顺手解绑");
        });
    }

    @Test
    @DisplayName("回合终态后归还额度，配额不会被永久占住")
    void chat_should_release_quota_when_turn_reaches_terminal_state() throws Exception {
        // Given：配额只有 1，没有配模型，因此回合会以失败告终——正好用来验「终态归还额度」
        runner("jellyfish.user.max-inflight-turns-per-user=1").run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");
            CountDownLatch terminal = new CountDownLatch(1);

            // When
            Submission submission = facade.chat("alice", sessionId, "你好", event -> {
                if (event.isTerminal()) {
                    terminal.countDown();
                }
            });
            assertEquals(Submission.Kind.STARTED_TURN, submission.getKind());
            assertNotNull(submission.getTurnId());
            assertTrue(awaitQuietly(terminal), "回合应当到达终态（没有模型时是 ERROR）");

            // Then：额度已归还，因此还能再发一次（若额度漏还，这里会抛 UserQuotaExceededException）
            CountDownLatch secondTerminal = new CountDownLatch(1);
            Submission second = facade.chat("alice", sessionId, "再来一次", event -> {
                if (event.isTerminal()) {
                    secondTerminal.countDown();
                }
            });
            assertEquals(Submission.Kind.STARTED_TURN, second.getKind());
            awaitQuietly(secondTerminal);
        });
    }

    @Test
    @DisplayName("并发回合超限时立刻拒绝，不排队等待；别人的额度不受影响")
    void chat_should_reject_when_user_quota_exhausted() {
        // When / Then
        runner("jellyfish.user.max-inflight-turns-per-user=1").run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            // 直接占住 alice 的额度：这样不必依赖「某个回合恰好还没结束」这种时序假设，
            // 断言才是确定的（回合失败得很快，靠它占额度会变成一条会偶发变红的用例）
            assertTrue(context.getBean(UserQuotaGuard.class).tryAcquireTurn("alice"));
            String aliceSession = facade.createSession("alice");
            String bobSession = facade.createSession("bob");

            UserQuotaExceededException thrown = assertThrows(UserQuotaExceededException.class,
                    () -> facade.chat("alice", aliceSession, "你好", event -> { }));
            assertEquals("alice", thrown.getUserId());

            // 别人的额度是独立的：bob 照样能发
            assertEquals(Submission.Kind.STARTED_TURN,
                    facade.chat("bob", bobSession, "你好", event -> { }).getKind());
        });
    }

    @Test
    @DisplayName("命令闸门在门面上生效：拒绝名单与越权都拦得住")
    void executeCommand_should_be_gated_when_called() {
        // When / Then
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            assertThrows(CommandNotAllowedException.class,
                    () -> facade.executeCommand("alice", sessionId, "/delete " + sessionId));
            assertThrows(CommandNotAllowedException.class,
                    () -> facade.executeCommand("alice", sessionId, "/session"));
            assertThrows(CommandNotAllowedException.class,
                    () -> facade.executeCommand("alice", null, "/model openai/gpt-4o"));
            // 会话内的只读命令正常
            assertFalse(facade.executeCommand("alice", sessionId, "/usage").isError());
        });
    }

    @Test
    @DisplayName("自定义归属存储可以替换默认实现")
    void customStore_should_replace_default_when_registered() {
        // When / Then
        runner().withBean(SessionOwnerStore.class, StubOwnerStore::new).run(context -> {
            assertThat(context).hasSingleBean(SessionOwnerStore.class);
            SessionOwnerStore store = context.getBean(SessionOwnerStore.class);
            assertThat(store).isInstanceOf(StubOwnerStore.class);

            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");

            // 存根放宽了归属判定：任何用户都认这个会话。
            // 若门面读的不是这个 Bean，这里就会是 false——这一条就是在证明「SPI 真的被用上了」
            assertTrue(store.owns("whoever", sessionId));
        });
    }

    /**
     * 等一个闩锁，中断时返回 {@code false} 而不抛异常。
     *
     * @param latch 闩锁
     * @return 等到返回 {@code true}
     */
    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(10L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 一个「把归属都算在自己身上」的存根，用来验证 SPI 可替换。
     *
     * @author zcd
     */
    static class StubOwnerStore implements SessionOwnerStore {

        /** 真实存储。 */
        private final InMemorySessionOwnerStore delegate = new InMemorySessionOwnerStore();

        @Override
        public void bind(String userId, String sessionId) {
            delegate.bind(userId, sessionId);
        }

        @Override
        public void unbind(String sessionId) {
            delegate.unbind(sessionId);
        }

        @Override
        public boolean owns(String userId, String sessionId) {
            // 刻意放宽：只要会话有归属就认，用来证明「门面读的确实是这个 Bean」
            return !delegate.ownerOf(sessionId).isEmpty();
        }

        @Override
        public java.util.List<String> sessionsOf(String userId) {
            return delegate.sessionsOf(userId);
        }

        @Override
        public String ownerOf(String sessionId) {
            return delegate.ownerOf(sessionId);
        }
    }
}
