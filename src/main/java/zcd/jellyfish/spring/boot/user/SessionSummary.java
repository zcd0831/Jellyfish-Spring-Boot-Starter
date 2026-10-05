package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionUsage;

/**
 * 会话概要：给业务侧列表页用的轻量投影，<b>不含消息正文</b>。
 * <p>
 * <b>为什么不直接暴露 {@code Session}</b>：{@code Session} 是内核的运行态对象，它的 {@code getMessages()}
 * 每次都复制整个消息列表（回合多的会话是 O(n) 的拷贝），而列表页一条正文都不需要。带上一份「只有概要」
 * 的投影，列表页再怎么翻也不会把整段对话拖进内存。
 * <p>
 * <b>要看正文时</b>走 {@code JellyfishUserFacade.snapshot(...)}，它返回内核的快照类型。
 * <p>
 * 不可变。
 *
 * @author zcd
 */
public final class SessionSummary {

    /** 会话标识。 */
    private final String sessionId;

    /** 会话标题，可能为空串（内核在首轮之后才会补标题）。 */
    private final String title;

    /** 绑定的 agentId，可能为 {@code null}（表示跟随默认）。 */
    private final String agentId;

    /** 模型 provider，可能为 {@code null}（表示跟随默认）。 */
    private final String provider;

    /** 模型名，可能为 {@code null}（表示跟随默认）。 */
    private final String model;

    /** 创建时刻（毫秒）。 */
    private final long createdAt;

    /**
     * 最后活动时刻（毫秒）。
     * <p>
     * 取自内核的 {@code Session.getUpdatedAt()}，也就是「最后一次让这个会话发生变化的时刻」。
     * 两个已知偏差：回合被插件拦下（用户消息没进会话）与回合一条消息都没留下时不刷新。
     */
    private final long updatedAt;

    /** 消息条数。 */
    private final int messageCount;

    /** token 用量累计。 */
    private final SessionUsage usage;

    /**
     * 构造概要。
     *
     * @param sessionId    会话标识
     * @param title        标题，可为 {@code null}
     * @param agentId      agentId，可为 {@code null}
     * @param provider     provider，可为 {@code null}
     * @param model        模型名，可为 {@code null}
     * @param createdAt    创建时刻
     * @param updatedAt    最后活动时刻
     * @param messageCount 消息条数
     * @param usage        用量，可为 {@code null}
     */
    private SessionSummary(String sessionId, String title, String agentId, String provider, String model,
                           long createdAt, long updatedAt, int messageCount, SessionUsage usage) {
        this.sessionId = sessionId;
        this.title = title == null ? "" : title;
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messageCount = messageCount;
        this.usage = usage;
    }

    /**
     * 从内核的运行态会话投影出概要。
     *
     * @param session 会话运行态，不可为 {@code null}
     * @return 概要，保证非 {@code null}
     */
    public static SessionSummary of(Session session) {
        return new SessionSummary(session.getSessionId(), session.getTitle(), session.getAgentId(),
                session.getProvider(), session.getModel(), session.getCreatedAt(), session.getUpdatedAt(),
                session.size(), session.getUsage());
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取标题。
     *
     * @return 标题；没有时为空串
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取 agentId。
     *
     * @return agentId，可能为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取 provider。
     *
     * @return provider，可能为 {@code null}
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取模型名。
     *
     * @return 模型名，可能为 {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取创建时刻。
     *
     * @return 创建时刻（毫秒）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取最后活动时刻。
     *
     * @return 最后活动时刻（毫秒）
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取 token 用量累计。
     *
     * @return 用量；内核未记账时为 {@code null}
     */
    public SessionUsage getUsage() {
        return usage;
    }
}
