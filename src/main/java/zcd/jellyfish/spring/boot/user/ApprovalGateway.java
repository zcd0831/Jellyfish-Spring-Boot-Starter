package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.infra.permission.ApprovalChannel;

import java.util.List;

/**
 * 审批网关：在 {@link ApprovalChannel} 之上补一层归属校验。
 * <p>
 * <b>为什么不能直接把通道交给业务侧</b>：内核的 {@code ApprovalChannel.resolve(id, approved)}
 * 只校验「这条请求是不是它<b>自己那个会话</b>的头槽位」——它<b>不校验调用方说的会话 id 对不对</b>。
 * 也就是说，如果业务侧拿着「我的会话 id + 别人的 requestId」去调 resolve，内核会照办：
 * 那个 requestId 确实是别人会话的头槽位，于是裁决成立。<b>任何人都能批准或拒绝别人的工具调用</b>，
 * 这正是多用户下最不能接受的一种越权。
 * <p>
 * 因此这里的每一条路径都先校验归属，并且 {@link #approve} 额外要求「这个 requestId 就是本会话队列的
 * 第一条」。两个条件都满足才转交内核。
 * <p>
 * 另一端也如实保留内核的既有语义：<b>只有头槽位那一条能被裁决</b>，排队中的请求裁决它等于无事发生
 * （所以 {@link #approve} 对它们返回 {@code false}，而不是「假装成功」）。
 *
 * @author zcd
 */
public class ApprovalGateway {

    /** 内核审批通道。 */
    private final ApprovalChannel approvals;

    /** 会话归属存储。 */
    private final SessionOwnerStore owners;

    /**
     * 构造网关。
     *
     * @param approvals 内核审批通道，不可为 {@code null}
     * @param owners    会话归属存储，不可为 {@code null}
     */
    public ApprovalGateway(ApprovalChannel approvals, SessionOwnerStore owners) {
        this.approvals = approvals;
        this.owners = owners;
    }

    /**
     * 取某会话当前待审批项：头槽位（可裁决的那一条）在前，其后是排队区。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 待审批项列表；没有时为空列表
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public List<ApprovalChannel.Pending> pendingApprovals(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        return approvals.pendingApprovals(sessionId);
    }

    /**
     * 裁决一条待审批项。
     * <p>
     * <b>刻意不直接用内核的无参 {@code pending()}</b>：它返回的是<b>跨会话最早</b>的那一条，
     * 在多个用户同时有待审批时会给出别人的。这里只认本会话自己的队列。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待审批项标识
     * @param approved  是否批准
     * @return 本次调用真的落定了一条返回 {@code true}；该请求不在本会话、或不是头槽位
     *         （已被裁决 / 已超时 / 还在排队）时返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean approve(String userId, String sessionId, String requestId, boolean approved) {
        requireOwned(userId, sessionId);
        if (requestId == null) {
            return false;
        }
        List<ApprovalChannel.Pending> queue = approvals.pendingApprovals(sessionId);
        if (queue.isEmpty() || !requestId.equals(queue.get(0).getId())) {
            // 不属于本会话，或不是头槽位。这一条判断就是上面那个越权口子的封堵点：
            // 少了它，把别人的 requestId 配自己的 sessionId 递进来就会被内核照单执行。
            return false;
        }
        return approvals.resolve(requestId, approved);
    }

    /**
     * 校验会话归属。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @throws SessionAccessDeniedException 不属于该用户时抛出
     */
    private void requireOwned(String userId, String sessionId) {
        if (!owners.owns(userId, sessionId)) {
            throw new SessionAccessDeniedException(userId, sessionId);
        }
    }
}
