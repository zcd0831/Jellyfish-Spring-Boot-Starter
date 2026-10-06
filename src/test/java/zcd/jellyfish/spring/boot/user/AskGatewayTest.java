package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.config.AskSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskGateway} 的单元测试：<b>它守的是越权 + 内容注入这两件事叠在一起的那条路径</b>。
 * <p>
 * 内核的 {@code AskChannel.resolve(id, answer)} 只校验「这条提问是不是它自己那个会话的头槽位」，
 * <b>不校验调用方说的会话 id 对不对</b>。因此「把我的 sessionId 配上别人的 requestId」递进去，
 * 内核会照单执行——等于任何人都能替别人回答模型的问题。而提问的答案会作为<b>工具结果原文</b>
 * 进那个会话的模型上下文，所以它同时还是一条把任意文本注入别人对话的路径。
 * <p>
 * 用真的 {@link AskChannel}（infra，除配置外无外部依赖）：要验的正是内核那套「只对头槽位生效」的语义，
 * mock 掉通道等于把被测对象赖以成立的约束抽走。提问挂在头槽位上这件事只能靠后台线程产生
 * （{@code request} 是阻塞的），与内核自己的用例同一手法。
 *
 * @author zcd
 */
@DisplayName("AskGateway 提问网关")
class AskGatewayTest {

    /** 等待上限：够长到不会在测试里自然超时，又短到失败时不挂死。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 真提问通道。 */
    private AskChannel channel;

    /** 归属存储。 */
    private InMemorySessionOwnerStore owners;

    /** 被测对象。 */
    private AskGateway gateway;

    @BeforeEach
    void setUp() {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getAskSettings()).thenReturn(new AskSettings());
        channel = new AskChannel(runtimeConfig);
        channel.attach();
        owners = new InMemorySessionOwnerStore();
        owners.bind("alice", "session-a");
        gateway = new AskGateway(channel, owners);
    }

    @AfterEach
    void tearDown() {
        channel.detach();
    }

    @Test
    @DisplayName("取待答提问要先过归属：别人的会话一律拒绝")
    void pendingAsks_should_reject_when_requester_is_not_the_owner() {
        // When / Then
        assertThrows(SessionAccessDeniedException.class, () -> gateway.pendingAsks("bob", "session-a"));
        assertThrows(SessionAccessDeniedException.class, () -> gateway.pendingAsks("alice", "session-b"));
    }

    @Test
    @DisplayName("本会话的待答提问：头槽位在前，排队区在后")
    void pendingAsks_should_return_head_first_when_requests_waiting() throws Exception {
        // Given
        AskRequest first = askFor("session-a");
        AskRequest second = askFor("session-a");
        Thread worker = askInBackground(first);
        awaitPending("session-a");
        Thread secondWorker = askInBackground(second);
        awaitWaiting("session-a", 1);

        try {
            // When
            List<AskChannel.Pending> queue = gateway.pendingAsks("alice", "session-a");

            // Then
            assertEquals(2, queue.size());
            assertEquals(first.getQuestion(), queue.get(0).getRequest().getQuestion());
            assertEquals(second.getQuestion(), queue.get(1).getRequest().getQuestion());
        } finally {
            channel.detach();
            worker.join(2000L);
            secondWorker.join(2000L);
        }
    }

    @Test
    @DisplayName("越权作答：拿自己的会话配别人的 requestId 必须失败")
    void answer_should_reject_when_request_belongs_to_another_session() throws Exception {
        // Given：bob 也有一个会话与一条待答提问
        owners.bind("bob", "session-b");
        Thread worker = askInBackground(askFor("session-b", "bob 的问题？"));
        awaitPending("session-b");
        String bobRequestId = channel.pending("session-b").orElse(null).getId();

        try {
            // When：alice 用<b>自己的</b> session-a 配上 bob 的 requestId
            // （内核的 resolve 只看「它是不是自己会话的头槽位」，所以少了网关这一层就会被作答）
            boolean answered = gateway.answer("alice", "session-a", bobRequestId, "a");

            // Then
            assertFalse(answered, "不该落定别人的提问");
            assertTrue(channel.pending("session-b").isPresent(), "bob 的提问应当还在等着");
        } finally {
            channel.detach();
            worker.join(2000L);
        }
    }

    @Test
    @DisplayName("越权作答：直接指定别人的会话也必须失败")
    void answer_should_reject_when_requester_does_not_own_the_session() throws Exception {
        // Given
        AskRequest request = askFor("session-a");
        Thread worker = askInBackground(request);
        awaitPending("session-a");
        String requestId = channel.pending("session-a").orElse(null).getId();

        try {
            // When / Then
            assertThrows(SessionAccessDeniedException.class,
                    () -> gateway.answer("bob", "session-a", requestId, "a"));
            assertTrue(channel.pending("session-a").isPresent());
        } finally {
            channel.detach();
            worker.join(2000L);
        }
    }

    @Test
    @DisplayName("作答自己的头槽位：选项标识传给等待中的工具")
    void answer_should_resolve_when_requester_owns_head_slot() throws Exception {
        // Given
        AtomicReference<AskAnswer> answer = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(askFor("session-a"), answer);
        awaitPending("session-a");
        String requestId = channel.pending("session-a").orElse(null).getId();

        // When
        boolean answered = gateway.answer("alice", "session-a", requestId, "b");

        // Then
        assertTrue(answered);
        worker.join(2000L);
        assertTrue(answer.get().isAnswered());
        assertEquals("b", answer.get().getOptionId());
        assertNull(answer.get().getText());
    }

    @Test
    @DisplayName("用户自己填的答案原样传给等待中的工具（前后空白去掉）")
    void answerWithText_should_resolve_with_custom_answer() throws Exception {
        // Given
        AtomicReference<AskAnswer> answer = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(askFor("session-a"), answer);
        awaitPending("session-a");
        String requestId = channel.pending("session-a").orElse(null).getId();

        // When
        boolean answered = gateway.answerWithText("alice", "session-a", requestId, "  都不合适  ");

        // Then
        assertTrue(answered);
        worker.join(2000L);
        assertEquals("都不合适", answer.get().getText());
        assertNull(answer.get().getOptionId());
    }

    @Test
    @DisplayName("放弃作答把等待中的工具放行，终态是 CANCELLED")
    void cancel_should_converge_pending_ask() throws Exception {
        // Given：客户端断开时接入方就靠它把 react 线程放开，而不是等满 120 秒
        AtomicReference<AskAnswer> answer = new AtomicReference<AskAnswer>();
        Thread worker = askInBackground(askFor("session-a"), answer);
        awaitPending("session-a");
        String requestId = channel.pending("session-a").orElse(null).getId();

        // When
        boolean cancelled = gateway.cancel("alice", "session-a", requestId, "客户端断开了");

        // Then
        assertTrue(cancelled);
        worker.join(2000L);
        assertEquals(AskAnswer.Status.CANCELLED, answer.get().getStatus());
        assertEquals("客户端断开了", answer.get().getReason());
        assertFalse(channel.pending("session-a").isPresent());
    }

    @Test
    @DisplayName("排队中的提问作答不了（内核只对头槽位生效）")
    void answer_should_reject_when_request_is_only_queued() throws Exception {
        // Given
        Thread headWorker = askInBackground(askFor("session-a"));
        awaitPending("session-a");
        String headId = channel.pending("session-a").orElse(null).getId();
        Thread queuedWorker = askInBackground(askFor("session-a"));
        awaitWaiting("session-a", 1);
        String queuedId = channel.pendingAsks("session-a").get(1).getId();

        try {
            // When：对排队中的那一条作答
            boolean answered = gateway.answer("alice", "session-a", queuedId, "a");

            // Then：如实返回 false（而不是「假装成功」）
            assertFalse(answered);
            assertEquals(headId, channel.pending("session-a").orElse(null).getId());
        } finally {
            channel.detach();
            headWorker.join(2000L);
            queuedWorker.join(2000L);
        }
    }

    @Test
    @DisplayName("空白的选项标识或答案原文不算作答，也不落定")
    void answer_should_reject_blank_payloads() {
        // When / Then：静默把它当成「用户放弃了」会让一次写错的请求看起来像用户自己的决定
        assertFalse(gateway.answer("alice", "session-a", "any-id", null));
        assertFalse(gateway.answer("alice", "session-a", "any-id", "   "));
        assertFalse(gateway.answerWithText("alice", "session-a", "any-id", null));
        assertFalse(gateway.answerWithText("alice", "session-a", "any-id", "\t"));
    }

    @Test
    @DisplayName("requestId 为 null 或不属于本会话时返回 false，不抛异常")
    void answer_should_return_false_when_request_id_is_unknown() {
        // When / Then
        assertFalse(gateway.answer("alice", "session-a", null, "a"));
        assertFalse(gateway.answer("alice", "session-a", "no-such-request", "a"));
        assertFalse(gateway.cancel("alice", "session-a", "no-such-request", null));
    }

    /**
     * 构造一条标准提问请求。
     *
     * @param sessionId 会话标识
     * @return 提问请求
     */
    private static AskRequest askFor(String sessionId) {
        return askFor(sessionId, "先做通道还是先做 UI？");
    }

    /**
     * 构造一条指定问题的提问请求。
     *
     * @param sessionId 会话标识
     * @param question  问题原文
     * @return 提问请求
     */
    private static AskRequest askFor(String sessionId, String question) {
        return AskRequest.of("ask_user", sessionId, question, Arrays.asList(
                AskOption.of("a", "先做通道", "内核与插件先落地"),
                AskOption.of("b", "先做 UI", null)));
    }

    /**
     * 在后台发起一次提问，让它挂到头槽位上。
     *
     * @param request 提问请求
     * @return 已启动的线程
     */
    private Thread askInBackground(AskRequest request) {
        return askInBackground(request, new AtomicReference<AskAnswer>());
    }

    /**
     * 在后台发起一次提问并记录答复。
     *
     * @param request 提问请求
     * @param answer  答复出口
     * @return 已启动的线程
     */
    private Thread askInBackground(AskRequest request, AtomicReference<AskAnswer> answer) {
        Thread thread = new Thread(() -> answer.set(channel.request(request, TIMEOUT)), "ask-gateway-test-worker");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等到提问真的挂到指定会话的头槽位上。
     *
     * @param sessionId 会话标识
     * @throws Exception 超时或中断
     */
    private void awaitPending(String sessionId) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (channel.pending(sessionId).isPresent()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("会话 " + sessionId + " 的提问未在预期时间内挂上");
    }

    /**
     * 等到指定会话的排队数达到预期值。
     *
     * @param sessionId 会话标识
     * @param expected  预期排队数
     * @throws Exception 超时或中断
     */
    private void awaitWaiting(String sessionId, int expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (channel.pendingAsks(sessionId).size() > expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("会话 " + sessionId + " 的排队数未在预期时间内超过 " + expected);
    }
}
