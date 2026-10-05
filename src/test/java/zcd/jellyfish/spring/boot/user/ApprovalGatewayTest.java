package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.infra.permission.ApprovalChannel;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApprovalGateway} 的单元测试：<b>它守的是多用户下最不能接受的一种越权</b>。
 * <p>
 * 内核的 {@code ApprovalChannel.resolve(id, approved)} 只校验「这条请求是不是它自己那个会话的头槽位」，
 * <b>不校验调用方说的会话 id 对不对</b>。因此「把我的 sessionId 配上别人的 requestId」递进去，
 * 内核会照单执行——等于任何人都能批准或拒绝别人的工具调用。网关必须在转交之前自己拦住这一类。
 * <p>
 * 用真的 {@link ApprovalChannel}（infra，无外部依赖）：要验的正是内核那套「只对头槽位生效」的语义，
 * mock 掉通道等于把被测对象赖以成立的约束抽走。请求挂在头槽位上这件事只能靠后台线程产生
 * （{@code request} 是阻塞的），与内核自己的用例同一手法。
 *
 * @author zcd
 */
@DisplayName("ApprovalGateway 审批网关")
class ApprovalGatewayTest {

    /** 等待上限：够长到不会在测试里自然超时，又短到失败时不挂死。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 真审批通道。 */
    private ApprovalChannel channel;

    /** 归属存储。 */
    private InMemorySessionOwnerStore owners;

    /** 被测对象。 */
    private ApprovalGateway gateway;

    @BeforeEach
    void setUp() {
        channel = new ApprovalChannel();
        channel.attach();
        owners = new InMemorySessionOwnerStore();
        owners.bind("alice", "session-a");
        gateway = new ApprovalGateway(channel, owners);
    }

    @AfterEach
    void tearDown() {
        channel.detach();
    }

    @Test
    @DisplayName("取待审批项要先过归属：别人的会话一律拒绝")
    void pendingApprovals_should_reject_when_requester_is_not_the_owner() {
        // When / Then
        assertThrows(SessionAccessDeniedException.class, () -> gateway.pendingApprovals("bob", "session-a"));
        assertThrows(SessionAccessDeniedException.class, () -> gateway.pendingApprovals("alice", "session-b"));
    }

    @Test
    @DisplayName("本会话的待审批项：头槽位在前")
    void pendingApprovals_should_return_head_first_when_requests_waiting() throws Exception {
        // Given
        ApprovalChannel.Pending first = pendingFor("session-a");
        ApprovalChannel.Pending second = pendingFor("session-a");
        Thread worker = requestInBackground(first);
        awaitPending("session-a");
        Thread secondWorker = requestInBackground(second);
        awaitWaiting("session-a", 1);

        try {
            // When
            List<ApprovalChannel.Pending> queue = gateway.pendingApprovals("alice", "session-a");

            // Then
            assertEquals(2, queue.size());
            assertEquals(first.getId(), queue.get(0).getId());
            assertEquals(second.getId(), queue.get(1).getId());
        } finally {
            channel.detach();
            worker.join(2000L);
            secondWorker.join(2000L);
        }
    }

    @Test
    @DisplayName("越权裁决：拿自己的会话配别人的 requestId 必须失败")
    void approve_should_reject_when_request_belongs_to_another_session() throws Exception {
        // Given：bob 也有一个会话与一条待审批项
        owners.bind("bob", "session-b");
        ApprovalChannel.Pending bobPending = pendingFor("session-b");
        Thread worker = requestInBackground(bobPending);
        awaitPending("session-b");

        try {
            // When：alice 用<b>自己的</b> session-a 配上 bob 的 requestId
            // （内核的 resolve 只看「它是不是自己会话的头槽位」，所以少了网关这一层就会被批准）
            boolean approved = gateway.approve("alice", "session-a", bobPending.getId(), true);

            // Then
            assertFalse(approved, "不该落定别人的审批");
            assertTrue(channel.pending("session-b").isPresent(), "bob 的审批应当还在等着");
        } finally {
            channel.detach();
            worker.join(2000L);
        }
    }

    @Test
    @DisplayName("越权裁决：直接指定别人的会话也必须失败")
    void approve_should_reject_when_requester_does_not_own_the_session() throws Exception {
        // Given
        ApprovalChannel.Pending pending = pendingFor("session-a");
        Thread worker = requestInBackground(pending);
        awaitPending("session-a");

        try {
            // When / Then
            assertThrows(SessionAccessDeniedException.class,
                    () -> gateway.approve("bob", "session-a", pending.getId(), true));
            assertTrue(channel.pending("session-a").isPresent());
        } finally {
            channel.detach();
            worker.join(2000L);
        }
    }

    @Test
    @DisplayName("裁决自己的头槽位成功")
    void approve_should_resolve_when_requester_owns_head_slot() throws Exception {
        // Given
        ApprovalChannel.Pending pending = pendingFor("session-a");
        AtomicReference<PermissionDecision> decision = new AtomicReference<PermissionDecision>();
        Thread worker = requestInBackground(pending, decision);
        awaitPending("session-a");

        // When
        boolean approved = gateway.approve("alice", "session-a", pending.getId(), true);

        // Then
        assertTrue(approved);
        worker.join(2000L);
        assertTrue(decision.get().isAllowed());
    }

    @Test
    @DisplayName("排队中的请求裁决不了（内核只对头槽位生效）")
    void approve_should_reject_when_request_is_only_queued() throws Exception {
        // Given
        ApprovalChannel.Pending head = pendingFor("session-a");
        ApprovalChannel.Pending queued = pendingFor("session-a");
        Thread headWorker = requestInBackground(head);
        awaitPending("session-a");
        Thread queuedWorker = requestInBackground(queued);
        awaitWaiting("session-a", 1);

        try {
            // When：对排队中的那一条裁决
            boolean approved = gateway.approve("alice", "session-a", queued.getId(), true);

            // Then：如实返回 false（而不是「假装成功」）
            assertFalse(approved);
            assertEquals(head.getId(), channel.pending("session-a").orElse(null).getId());
        } finally {
            channel.detach();
            headWorker.join(2000L);
            queuedWorker.join(2000L);
        }
    }

    @Test
    @DisplayName("requestId 为 null 或不属于本会话时返回 false，不抛异常")
    void approve_should_return_false_when_request_id_is_unknown() {
        // When / Then
        assertFalse(gateway.approve("alice", "session-a", null, true));
        assertFalse(gateway.approve("alice", "session-a", "no-such-request", true));
    }

    /**
     * 构造一条待审批项。
     *
     * @param sessionId 会话标识
     * @return 待审批项
     */
    private static ApprovalChannel.Pending pendingFor(String sessionId) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("command", "rm -rf build");
        return new ApprovalChannel.Pending(sessionId, "agent-a", "shell", arguments, "策略要求人工审批");
    }

    /**
     * 在后台发起一次审批请求，让它挂到头槽位上。
     *
     * @param pending 待审批项
     * @return 已启动的线程
     */
    private Thread requestInBackground(ApprovalChannel.Pending pending) {
        return requestInBackground(pending, new AtomicReference<PermissionDecision>());
    }

    /**
     * 在后台发起一次审批请求并记录结论。
     *
     * @param pending  待审批项
     * @param decision 结论出口
     * @return 已启动的线程
     */
    private Thread requestInBackground(ApprovalChannel.Pending pending,
                                       AtomicReference<PermissionDecision> decision) {
        Thread thread = new Thread(() -> decision.set(channel.request(pending, TIMEOUT)),
                "approval-gateway-test-worker");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 等到请求真的挂到指定会话的头槽位上。
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
        throw new IllegalStateException("会话 " + sessionId + " 的审批请求未在预期时间内挂上");
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
            if (channel.pendingApprovals(sessionId).size() > expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new IllegalStateException("会话 " + sessionId + " 的排队数未在预期时间内达到 " + expected);
    }
}
