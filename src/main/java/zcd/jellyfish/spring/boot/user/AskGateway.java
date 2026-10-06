package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.infra.ask.AskChannel;

import java.util.List;

/**
 * 提问网关：在 {@link AskChannel} 之上补一层归属校验。
 * <p>
 * <b>为什么不能直接把通道交给业务侧</b>：内核的 {@code AskChannel.resolve(id, answer)}
 * 只校验「这条请求是不是它<b>自己那个会话</b>的头槽位」——它<b>不校验调用方说的会话 id 对不对</b>。
 * 如果业务侧拿着「我的会话 id + 别人的 requestId」去调 resolve，内核会照办：
 * 那个 requestId 确实是别人会话的头槽位，于是作答成立。<b>任何人都能替别人回答模型的问题。</b>
 * <p>
 * <b>它比审批的同类漏洞更严重，值得单独说</b>：审批的越权后果是「替别人放行/拒绝一次工具调用」；
 * 而提问的答案会作为<b>工具结果原文回灌进那个会话的模型上下文</b>。
 * 因此除了越权，它还是一条<b>内容注入</b>路径——攻击者可以借此把任意文本塞进别人的对话里，
 * 让模型把「用户说过的话」当成事实。两者的封堵点相同（校验归属 + 只认本会话头槽位），
 * 但这里更不能漏。
 * <p>
 * 另一端也如实保留内核的既有语义：<b>只有头槽位那一条能被作答</b>，排队中的提问作答它等于无事发生
 * （所以 {@link #answer} 对它们返回 {@code false}，而不是「假装成功」）。
 * <p>
 * 无状态（只持有通道与归属存储），可安全跨线程调用。
 *
 * @author zcd
 */
public class AskGateway {

    /** 内核提问通道。 */
    private final AskChannel asks;

    /** 会话归属存储。 */
    private final SessionOwnerStore owners;

    /**
     * 构造网关。
     *
     * @param asks   内核提问通道，不可为 {@code null}
     * @param owners 会话归属存储，不可为 {@code null}
     */
    public AskGateway(AskChannel asks, SessionOwnerStore owners) {
        this.asks = asks;
        this.owners = owners;
    }

    /**
     * 取某会话当前待答提问：头槽位（可作答的那一条）在前，其后是排队区。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 待答提问列表；没有时为空列表
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public List<AskChannel.Pending> pendingAsks(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        return asks.pendingAsks(sessionId);
    }

    /**
     * 作答：用户从候选项里选了一个。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param optionId  选中的选项标识，不可为空白
     * @return 本次调用真的落定了一条返回 {@code true}；该请求不在本会话、或不是头槽位
     *         （已被作答 / 已超时 / 还在排队）时返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean answer(String userId, String sessionId, String requestId, String optionId) {
        if (optionId == null || optionId.trim().isEmpty()) {
            // 空白选项标识不是一次作答：它与「请求写错了」无法区分，静默当成「用户放弃了」更糟
            return false;
        }
        return resolve(userId, sessionId, requestId, AskAnswer.answered(optionId.trim()));
    }

    /**
     * 作答：用户自己填了一个答案。
     * <p>
     * <b>文本会原样回灌给模型</b>（作为工具结果），因此调用方必须先确认这是该会话归属者的输入——
     * 归属校验在 {@link #resolve} 里，这一条不是可选的。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param text      用户自己填的答案原文，不可为空白
     * @return 本次调用真的落定了一条返回 {@code true}；其余情形返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean answerWithText(String userId, String sessionId, String requestId, String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return resolve(userId, sessionId, requestId, AskAnswer.custom(text.trim()));
    }

    /**
     * 放弃作答：让卡在提问上的回合立刻继续。
     * <p>
     * <b>为什么需要它</b>：接入方的客户端断开、用户关掉页面时，若不收敛，那条 {@code react} 线程
     * 要一直阻塞到超时（缺省 120 秒）；而 {@code react} 池只有 8 条线程（= 并发顶层回合上限）。
     * 它对应的终态是「用户取消了这次提问」——工具会把这个结果回灌给模型，回合照常跑完。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param reason    说明，可为 {@code null}
     * @return 本次调用真的落定了一条返回 {@code true}；其余情形返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean cancel(String userId, String sessionId, String requestId, String reason) {
        return resolve(userId, sessionId, requestId, AskAnswer.cancelled(reason));
    }

    /**
     * 校验归属并作答。
     * <p>
     * <b>刻意不直接用内核的无参 {@code pending()}</b>：它返回的是<b>跨会话最早</b>的那一条，
     * 在多个用户同时有待答提问时会给出别人的。这里只认本会话自己的队列。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param answer    答复，不可为 {@code null}
     * @return 本次调用真的落定了一条返回 {@code true}；其余情形返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    private boolean resolve(String userId, String sessionId, String requestId, AskAnswer answer) {
        requireOwned(userId, sessionId);
        if (requestId == null) {
            return false;
        }
        List<AskChannel.Pending> queue = asks.pendingAsks(sessionId);
        if (queue.isEmpty() || !requestId.equals(queue.get(0).getId())) {
            // 不属于本会话，或不是头槽位。这一条判断就是上面那个越权口子的封堵点：
            // 少了它，把别人的 requestId 配自己的 sessionId 递进来就会被内核照单执行。
            return false;
        }
        return asks.resolve(requestId, answer);
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
