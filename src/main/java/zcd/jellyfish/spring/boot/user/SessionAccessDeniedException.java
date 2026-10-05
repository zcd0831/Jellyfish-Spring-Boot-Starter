package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.api.JellyfishException;

/**
 * 跨用户访问会话时抛出：请求者不是该会话的归属者，或该会话根本不存在。
 * <p>
 * <b>为什么「不存在」与「不属于你」用同一个异常</b>：两者对请求者应当给出<b>同样</b>的答复。
 * 若分开报错，攻击者就能用「不属于你」这个更明确的错误去枚举出「哪些会话 id 真实存在」——
 * 会话 id 是 UUID，本身不好猜，但这个区分等于把可猜空间从「猜中 UUID」降到「猜中前缀」。
 * 因此对外一律是「找不到」；谁是谁的会话只在日志里可见。
 * <p>
 * 内核里没有「用户」这一维（{@code Session} 上没有 owner 字段），因此这条判定只能由本层提供。
 *
 * @author zcd
 */
public class SessionAccessDeniedException extends JellyfishException {

    /** 请求者标识（仅用于日志，不要回显给请求者）。 */
    private final String userId;

    /** 会话标识。 */
    private final String sessionId;

    /**
     * 构造异常。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     */
    public SessionAccessDeniedException(String userId, String sessionId) {
        // 消息刻意不含 userId：异常消息常被直接回显给调用方，带上它等于确认了「这个会话属于别人」
        super("会话不存在或无权访问：" + sessionId);
        this.userId = userId;
        this.sessionId = sessionId;
    }

    /**
     * 获取请求者标识。
     * <p>
     * 只应写进服务端日志，不要放进 HTTP 响应体。
     *
     * @return 请求者标识
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }
}
