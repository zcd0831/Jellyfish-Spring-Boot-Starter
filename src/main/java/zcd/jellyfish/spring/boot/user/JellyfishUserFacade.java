package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.core.conversation.SubmissionPolicy;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionSnapshots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多用户门面：接入方与内核之间的<b>唯一</b>入口，每个方法都以 {@code userId} 开头。
 * <p>
 * <b>它补的是什么</b>：内核不认识「用户」。{@code Session} 上没有 owner 字段、
 * {@code SessionManager.all()} 返回全进程会话、命令域可以直接操作任意会话、审批与提问通道的 resolve
 * 也不校验调用方说的会话是不是自己的。这些在「一个人在自己终端上操作」时都不是问题，
 * 而接进多用户服务之后每一条都是越权或泄漏。本类把这些判定收在一处，让业务侧<b>无从绕过</b>。
 * <p>
 * <b>为什么不把内核的门面直接给业务侧</b>：那样每个业务类都要自己记得「先查归属」，
 * 而漏掉一处就是一个静默的越权漏洞。收成一个入口之后，漏掉的可能性只剩「绕过本类直接用内核 Bean」——
 * 那是可以靠代码评审与文档禁止的。
 * <p>
 * <b>三条贯穿全类的不变量</b>：
 * <ol>
 *     <li><b>永不调用 {@code switchTo} / {@code createDefault}</b>。前者写进程级的「当前会话」指针，
 *     多用户下会让一个用户的会话变成所有人的「当前会话」；后者读的是同样进程级的
 *     {@code SessionDefaults}。建会话一律用 {@code create(agentId, provider, model)} 并显式记下归属。</li>
 *     <li><b>提交策略恒为 {@link SubmissionPolicy#serverChat()}</b>（{@code commands=false,
 *     directives=false, REQUIRE_EXISTING}）。命令与输入指令是多用户下的危险面（见 {@link CommandGate}），
 *     因此默认不走；命令另有 {@link #executeCommand} 这条带闸门的通道。</li>
 *     <li><b>凡是按会话操作的路径都先过归属校验</b>，且「不存在」与「不属于你」对外是同一个答复
 *     （见 {@link SessionAccessDeniedException}）。</li>
 * </ol>
 * <p>
 * 线程安全：本类无可变状态，所有可变状态都在内核或被注入的组件里（它们各自线程安全）。
 *
 * @author zcd
 */
public class JellyfishUserFacade {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(JellyfishUserFacade.class);

    /**
     * 建会话时用的并发保护槽位数。
     * <p>
     * 用固定数量的槽位按用户标识散列，而不是给每个用户各建一把锁：后者会随用户数无限增长，
     * 而建会话是纯内存操作、毫秒级，不同用户偶尔撞到同一把锁的代价可以忽略。
     */
    private static final int CREATE_LOCK_STRIPES = 64;

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** 会话提交服务。 */
    private final ConversationService conversation;

    /** 外壳通道门面。 */
    private final ShellStreams streams;

    /** 在途回合表。 */
    private final TurnRegistry turns;

    /** 命令域服务。 */
    private final CommandManager commands;

    /** 会话归属存储。 */
    private final SessionOwnerStore owners;

    /** 每用户配额。 */
    private final UserQuotaGuard quota;

    /** 审批网关。 */
    private final ApprovalGateway approvals;

    /** 提问网关。 */
    private final AskGateway asks;

    /** 命令闸门。 */
    private final CommandGate gate;

    /** 建会话用的散列锁。 */
    private final Object[] createLocks = new Object[CREATE_LOCK_STRIPES];

    /**
     * 构造门面。
     *
     * @param sessions     会话域服务，不可为 {@code null}
     * @param conversation 会话提交服务，不可为 {@code null}
     * @param streams      外壳通道门面，不可为 {@code null}
     * @param turns        在途回合表，不可为 {@code null}
     * @param commands     命令域服务，不可为 {@code null}
     * @param owners       会话归属存储，不可为 {@code null}
     * @param quota        每用户配额，不可为 {@code null}
     * @param approvals    审批网关，不可为 {@code null}
     * @param asks         提问网关，不可为 {@code null}
     * @param gate         命令闸门，不可为 {@code null}
     */
    public JellyfishUserFacade(SessionManager sessions, ConversationService conversation, ShellStreams streams,
                               TurnRegistry turns, CommandManager commands, SessionOwnerStore owners,
                               UserQuotaGuard quota, ApprovalGateway approvals, AskGateway asks,
                               CommandGate gate) {
        this.sessions = sessions;
        this.conversation = conversation;
        this.streams = streams;
        this.turns = turns;
        this.commands = commands;
        this.owners = owners;
        this.quota = quota;
        this.approvals = approvals;
        this.asks = asks;
        this.gate = gate;
        for (int i = 0; i < createLocks.length; i++) {
            createLocks[i] = new Object();
        }
    }

    /**
     * 新建会话并记下归属（跟随默认 agent 与默认模型）。
     *
     * @param userId 归属者标识，不可为空白
     * @return 新会话标识，保证非空白
     * @throws UserQuotaExceededException 已到会话数上限时抛出
     */
    public String createSession(String userId) {
        return createSession(userId, null, null, null);
    }

    /**
     * 新建会话并记下归属。
     * <p>
     * <b>为什么用 {@code create(agentId, provider, model)} 而不是 {@code createDefault()}</b>：
     * 后者读的是进程级的 {@code SessionDefaults}，那是「一个人一条命令改了默认值」的模型；
     * 显式传 {@code null} 的语义是「跟随全局默认」，与调用者是谁无关。
     *
     * @param userId    归属者标识，不可为空白
     * @param agentId   agentId，可为 {@code null}（跟随默认）
     * @param provider  模型 provider，可为 {@code null}（跟随默认）
     * @param model     模型名，可为 {@code null}（跟随默认）
     * @return 新会话标识，保证非空白
     * @throws UserQuotaExceededException 已到会话数上限时抛出
     */
    public String createSession(String userId, String agentId, String provider, String model) {
        synchronized (createLockFor(userId)) {
            // 配额检查与建会话必须在同一把锁里：「先查数再建」分开做的话，
            // 同一用户的两个并发请求会各自看到「还差一个」，然后各建一个，配额形同虚设
            quota.requireSessionSlot(userId, owners.countOf(userId));
            Session session = sessions.create(agentId, provider, model);
            owners.bind(userId, session.getSessionId());
            return session.getSessionId();
        }
    }

    /**
     * 列出某用户的会话概要（按最后活动时间从新到旧）。
     * <p>
     * 顺带做一次懒清理：归属表里有、而会话表里已经没有的记录会被解绑。这件事本该由回收器做，
     * 但会话也可能因别的原因消失（例如直接调内核 API 关掉、或持久化插件在启动时没恢复成功），
     * 在读取路径上顺手清掉最省事，也避免了归属表悄悄长大。
     *
     * @param userId 归属者标识
     * @return 会话概要列表；没有时为空列表
     */
    public List<SessionSummary> listSessions(String userId) {
        List<SessionSummary> result = new ArrayList<SessionSummary>();
        for (String sessionId : owners.sessionsOf(userId)) {
            if (!sessions.exists(sessionId)) {
                owners.unbind(sessionId);
                continue;
            }
            result.add(SessionSummary.of(sessions.require(sessionId)));
        }
        result.sort((left, right) -> Long.compare(right.getUpdatedAt(), left.getUpdatedAt()));
        return result;
    }

    /**
     * 取会话的完整快照（含消息正文与用量）。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 快照，保证非 {@code null}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public SessionSnapshot snapshot(String userId, String sessionId) {
        return SessionSnapshots.capture(requireOwned(userId, sessionId));
    }

    /**
     * 关闭会话：派发给持久化处理器后从会话表摘掉（用户还能通过持久化插件把它恢复回来）。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public void closeSession(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        // 先取消在途回合：关掉一个正在跑的会话，之后每次消息追加都会失败，
        // 现场表现是「会话没了，但模型还在写」。取消是协作式的，这里不等待它收敛
        turns.cancel(sessionId);
        try {
            sessions.close(sessionId, SessionBeforeCloseRequest.Reason.USER_REQUEST);
        } finally {
            owners.unbind(sessionId);
        }
    }

    /**
     * 删除会话：连同持久化内容一起抹掉。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public void deleteSession(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        turns.cancel(sessionId);
        try {
            sessions.delete(sessionId);
        } finally {
            owners.unbind(sessionId);
        }
    }

    /**
     * 发一次对话：<b>业务侧发起回合的唯一入口</b>。
     * <p>
     * 回合是异步的：本方法返回时回合才刚开始，结果通过 {@code listener} 以
     * {@link ShellTurnEvent} 的形式推给你（终态是四条互斥事件之一，用 {@code event.isTerminal()} 判断）。
     * <p>
     * <b>listener 在内核线程上被调用</b>（{@code react} 线程、{@code llm-stream} 线程、
     * 工具输出泵线程——后两者会并发），因此它<b>必须快且线程安全</b>。写 SSE 这类 I/O 应当自己做缓冲或
     * 转投到别的线程；listener 抛出的异常会被本类记一条日志后吞掉，以免影响内核的事件派发。
     * <p>
     * 每次调用都会占用该用户一个回合额度，直到回合进入终态才归还。因此 listener 收到终态之后
     * <b>不要再期待更多事件</b>——订阅已在本类内部关闭。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param text      用户输入
     * @param listener  回合事件回调，不可为 {@code null}
     * @return 提交结果：正常是 {@code Kind.STARTED_TURN}（此时 {@code getTurnId()} 非 {@code null}）；
     *         插件改写了输入并接管时为 {@code Kind.HANDLED_INPUT}；会话不存在等为 {@code Kind.REJECTED}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     * @throws UserQuotaExceededException   该用户并发回合已到上限时抛出
     * @throws zcd.jellyfish.core.conversation.TurnInProgressException 同一会话已有在途回合时抛出
     */
    public Submission chat(String userId, String sessionId, String text, ShellTurnListener listener) {
        requireOwned(userId, sessionId);
        if (!quota.tryAcquireTurn(userId)) {
            throw new UserQuotaExceededException(userId, "同时进行的对话已达上限，请等前一轮结束后再试");
        }
        QuotaReleasingListener tracked = new QuotaReleasingListener(userId, listener, quota);
        try {
            // 必须先订阅再提交：回合一启动就会产出事件，晚订阅会丢掉开头那一段
            // （STARTED 之后紧接着可能就是 TEXT）。这条纪律与内核的三个外壳完全一致
            tracked.attach(streams.subscribe(sessionId, tracked));
            Submission submission = conversation.submit(sessionId, text,
                    InputTransformRequest.Source.SERVER, SubmissionPolicy.serverChat());
            if (submission.getKind() != Submission.Kind.STARTED_TURN) {
                // 没起回合就不会有终态事件，额度得在这里还掉，否则这个用户永久少一个额度
                tracked.finish();
            }
            return submission;
        } catch (RuntimeException e) {
            // 起回合失败（会话被并发删掉、已有在途回合、插件缺陷……）：同样没有终态事件
            tracked.finish();
            throw e;
        }
    }

    /**
     * 取消某会话的在途回合。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 确实取消到了一个在途回合返回 {@code true}；没有在跑（或已收敛）返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean cancel(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        return turns.cancel(sessionId);
    }

    /**
     * 判断某会话是否有在途回合（诊断用）。
     * <p>
     * <b>不要拿它做「能不能提交」的前置判断</b>：读到结果的那一刻它就可能已经变了。
     * 并发冲突交给 {@link #chat} 抛出的 {@code TurnInProgressException}。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 有在途回合返回 {@code true}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean isTurnRunning(String userId, String sessionId) {
        requireOwned(userId, sessionId);
        return turns.isRunning(sessionId);
    }

    /**
     * 取某会话当前待审批项：头槽位（唯一可裁决的那一条）在前，其后是排队区。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 待审批项列表；没有时为空列表
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public List<ApprovalChannel.Pending> pendingApprovals(String userId, String sessionId) {
        return approvals.pendingApprovals(userId, sessionId);
    }

    /**
     * 裁决一条待审批项。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待审批项标识
     * @param approved  是否批准
     * @return 本次调用真的落定了一条返回 {@code true}；该请求不在本会话、或已不是头槽位返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean approve(String userId, String sessionId, String requestId, boolean approved) {
        return approvals.approve(userId, sessionId, requestId, approved);
    }

    /**
     * 取某会话当前待答提问：头槽位（唯一可作答的那一条）在前，其后是排队区。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 待答提问列表；没有时为空列表
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public List<AskChannel.Pending> pendingAsks(String userId, String sessionId) {
        return asks.pendingAsks(userId, sessionId);
    }

    /**
     * 作答：用户从候选项里选了一个。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param optionId  选中的选项标识
     * @return 本次调用真的落定了一条返回 {@code true}；该请求不在本会话、或已不是头槽位返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean answer(String userId, String sessionId, String requestId, String optionId) {
        return asks.answer(userId, sessionId, requestId, optionId);
    }

    /**
     * 作答：用户自己填了一个答案。
     * <p>
     * 文本会原样回灌给模型（作为工具结果），因此<b>归属校验是这条路径的前提</b>——
     * 少了它就是一条把任意文本注入别人对话的路径。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param text      用户自己填的答案原文，不可为空白
     * @return 本次调用真的落定了一条返回 {@code true}；其余情形返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean answerWithText(String userId, String sessionId, String requestId, String text) {
        return asks.answerWithText(userId, sessionId, requestId, text);
    }

    /**
     * 放弃作答：客户端断开、用户关掉页面时用它把卡在提问上的回合放行。
     * <p>
     * 不调它的话，那条 {@code react} 线程要阻塞到超时（缺省 120 秒），而那个池只有 8 条线程。
     * 终态是「用户取消了这次提问」，模型据此继续，回合照常跑完。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param requestId 待答提问标识
     * @param reason    说明，可为 {@code null}
     * @return 本次调用真的落定了一条返回 {@code true}；其余情形返回 {@code false}
     * @throws SessionAccessDeniedException 不是该会话的归属者时抛出
     */
    public boolean cancelAsk(String userId, String sessionId, String requestId, String reason) {
        return asks.cancel(userId, sessionId, requestId, reason);
    }

    /**
     * 执行一条命令。
     * <p>
     * 先过 {@link CommandGate}（拒绝会碰进程级状态或别人会话的命令），再校验会话归属，最后才交给内核。
     * 之所以要一条带闸门的通道而不是直接放行命令域：命令的参数部分<b>不受归属校验保护</b>——
     * {@code /delete <别人的会话 id>} 就是一个例子，闸门是唯一能拦住它的地方。
     * <p>
     * 命令是<b>同步</b>执行的（与回合不同），返回值即结果。
     *
     * @param userId      请求者标识
     * @param sessionId   会话标识，可为 {@code null}（仅对不需要会话的命令成立，例如 {@code /help}）
     * @param commandLine 命令原文，形如 {@code /compact preview}
     * @return 命令结果，保证非 {@code null}
     * @throws CommandNotAllowedException   命令被闸门拒绝时抛出
     * @throws SessionAccessDeniedException 给了会话但请求者不是它的归属者时抛出
     */
    public CommandResult executeCommand(String userId, String sessionId, String commandLine) {
        gate.check(commandLine, sessionId);
        if (sessionId != null) {
            requireOwned(userId, sessionId);
        }
        return commands.execute(commandLine, sessionId);
    }

    /**
     * 校验归属并取回会话。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 会话运行态，保证非 {@code null}
     * @throws SessionAccessDeniedException 不是该会话的归属者、或会话已不存在时抛出
     */
    private Session requireOwned(String userId, String sessionId) {
        if (!owners.owns(userId, sessionId) || !sessions.exists(sessionId)) {
            // 两种情况回同一个异常：告诉请求者「这个 id 存在但不是你的」等于给了他一个枚举工具
            throw new SessionAccessDeniedException(userId, sessionId);
        }
        return sessions.require(sessionId);
    }

    /**
     * 取建会话用的散列锁。
     *
     * @param userId 归属者标识，可为 {@code null}
     * @return 锁对象，保证非 {@code null}
     */
    private Object createLockFor(String userId) {
        int hash = userId == null ? 0 : userId.hashCode();
        return createLocks[Math.abs(hash % CREATE_LOCK_STRIPES)];
    }
}
