package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.spring.boot.JellyfishLifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 闲置会话回收：把长时间没人用的会话关掉，释放内存。
 * <p>
 * <b>为什么需要它</b>：内核的会话表是一个只增不减的 {@code ConcurrentHashMap}——没有 TTL、没有淘汰、
 * 没有数量上限，而且<b>整段消息历史常驻内存</b>。在单用户 CLI 里这不是问题（进程活不了多久），
 * 在多用户服务里它就是一条明确的泄漏路径：每次会话都留下来，直到进程重启。
 * <p>
 * <b>两道必须有的判断</b>：
 * <ol>
 *     <li><b>跳过有在途回合的会话</b>。闲置判据是「最后一次变更距今多久」，而一次长 LLM 调用在新消息
 *     落库之前<b>不会更新这个时间</b>——只看时间戳会把正在跑的回合当成闲置会话关掉，
 *     现场表现是「模型还在回答，会话却没了」。</li>
 *     <li><b>只回收有归属者的会话</b>。没有归属记录的会话不是本层建的（例如直接调内核 API 建的），
 *     关掉它超出了本层的职责。</li>
 * </ol>
 * <p>
 * <b>为什么用 {@code close} 而不是 {@code delete}</b>：{@code close} 会先把会话派发给持久化处理器再摘掉，
 * 也就是「正常收工」；{@code delete} 是「连同落盘一起抹掉」。回收是空间管理，不是用户的删除意图——
 * 后者由 {@code deleteSession} 表达。
 * <p>
 * <b>{@code jellyfish.user.idle-timeout ≤ 0} 表示不回收</b>（缺省同理），此时既不排扫描、
 * {@link #sweep()} 也一次不关。它与 {@code reaper-enabled=false} 的区别只是「谁表达了不回收」：
 * 那个开关说的是「不要这个功能」，而这个值说的是「没有闲置这回事」。两条路都不能折算成
 * 「很小的门槛」——门槛决定的是关不关、关多少，配错方向的代价是「每轮扫描清空所有空闲会话」，
 * 而客户端看到的只是自己的 {@code sessionId} 突然 404。
 * <p>
 * <b>phase 比内核大 1</b>：回收集的任务必须在内核<b>启动之后</b>才开始、在内核<b>关闭之前</b>就停下
 * （停止按 phase 降序执行）。反过来的话，关闭过程中回收器可能正拿着一个已经收敛的会话表在调 close。
 *
 * @author zcd
 */
public class IdleSessionReaper implements SmartLifecycle {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(IdleSessionReaper.class);

    /**
     * 「不回收」的内部标记：来自 {@code idle-timeout ≤ 0}（或缺省）。
     * <p>
     * <b>为什么不能折算成一个很小的门槛</b>：门槛的作用是筛出「很久没人用」的会话，而它的判据是
     * {@code updatedAt < now - 门槛}。门槛取 1 毫秒时这个条件对几乎所有会话都成立，于是每一轮扫描
     * 都会把所有无在途回合的已归属会话关掉并解绑——配置的人以为自己写的是「不限」，
     * 实际得到的是「每 5 分钟清空一次」。这两件事必须分开表达，因此用一个不会与任何真实门槛混淆的值。
     */
    private static final long REAP_DISABLED = -1L;

    /**
     * 扫描间隔的下限（毫秒）。
     * <p>
     * <b>为什么只钳扫描间隔、不钳闲置门槛</b>：会造成忙等的是「每隔多久醒一次」，不是「多旧算闲置」。
     * 门槛配得很小（哪怕 1 毫秒）也只是把关得早一点，代价可控；而扫描间隔配成 1 毫秒会让这条调度线程
     * 一直空转。两者的下限因此刻意不同——把门槛也钳到 1 秒会让「按秒试跑」这种合理用法变得不可能。
     */
    private static final long MIN_SWEEP_INTERVAL_MILLIS = 1000L;

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** 会话归属存储。 */
    private final SessionOwnerStore owners;

    /** 在途回合表：用来跳过正在跑的会话。 */
    private final TurnRegistry turns;

    /** 闲置判定时长（毫秒）；{@link #REAP_DISABLED} 表示不回收。 */
    private final long idleTimeoutMillis;

    /** 扫描间隔（毫秒）。 */
    private final long sweepIntervalMillis;

    /** 扫描线程池；只在 {@link #start()} 之后存在。 */
    private volatile ScheduledExecutorService executor;

    /** 是否在运行。 */
    private volatile boolean running;

    /**
     * 构造回收器。
     *
     * @param sessions   会话域服务，不可为 {@code null}
     * @param owners     会话归属存储，不可为 {@code null}
     * @param turns      在途回合表，不可为 {@code null}
     * @param properties 配置，不可为 {@code null}
     */
    public IdleSessionReaper(SessionManager sessions, SessionOwnerStore owners, TurnRegistry turns,
                             JellyfishUserProperties properties) {
        this.sessions = sessions;
        this.owners = owners;
        this.turns = turns;
        this.idleTimeoutMillis = idleTimeoutMillis(properties.getIdleTimeout());
        this.sweepIntervalMillis = Math.max(MIN_SWEEP_INTERVAL_MILLIS,
                properties.getSweepInterval().toMillis());
    }

    /**
     * 把配置里的闲置门槛翻成内部值。
     * <p>
     * <b>{@code ≤ 0} 或缺省一律是「不回收」</b>，不是「折算成一个很小的门槛」——后者会把
     * 「以为自己在说不限」的配置变成「每轮扫描清空所有空闲会话」。缺省（{@code null}）走同一条路：
     * 属性没配过时 Spring 根本不会调 setter，会走到这里的 {@code null} 只可能来自程序化配置，
     * 而「不知道」不该被解释成一个具体的门槛。
     *
     * @param configured 配置里的闲置时长，可为 {@code null}
     * @return 闲置门槛毫秒数；不回收时为 {@link #REAP_DISABLED}
     */
    private static long idleTimeoutMillis(Duration configured) {
        if (configured == null) {
            return REAP_DISABLED;
        }
        long millis = configured.toMillis();
        return millis <= 0L ? REAP_DISABLED : millis;
    }

    @Override
    public int getPhase() {
        // 内核启动之后才开始、关闭之前就停下（停止按 phase 降序）
        return JellyfishLifecycle.PHASE + 1;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        if (idleTimeoutMillis == REAP_DISABLED) {
            // 不排扫描、也不算「在运行」：空闲的调度线程也是一种占用，而它什么都不会做。
            // 这条日志要显眼——「不回收」的代价是会话表只增不减（内核没有 TTL、没有淘汰、没有数量上限），
            // 这是一个需要有人知情的选择，不是默认值。
            LOG.warn("jellyfish.user.idle-timeout 为 0 或负数（或缺省），按「不回收」处理，回收器不启动；"
                    + "会话及其整段消息历史将常驻内存，只增不减");
            return;
        }
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(new ReaperThreadFactory());
        created.scheduleWithFixedDelay(this::sweepQuietly, sweepIntervalMillis, sweepIntervalMillis,
                TimeUnit.MILLISECONDS);
        this.executor = created;
        this.running = true;
        LOG.info("闲置会话回收已启动：闲置判定 {} 分钟，扫描间隔 {} 分钟",
                idleTimeoutMillis / 60000L, sweepIntervalMillis / 60000L);
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = this.executor;
        this.executor = null;
        this.running = false;
        if (current == null) {
            return;
        }
        // shutdownNow 而不是 shutdown：守在调度上的线程可能正在等下一次触发，等它自然退出没有意义。
        // 已经进入 sweep() 的那一次让它跑完（sweep 自身不做阻塞 I/O，代价可控）
        current.shutdownNow();
        LOG.info("闲置会话回收已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 扫描一次，任何异常都只记日志。
     * <p>
     * 调度式任务里抛异常会让后续触发<b>静默停止</b>（{@code scheduleWithFixedDelay} 的语义），
     * 于是「回收不工作了」这件事不会有任何提示。因此这里必须兜住。
     */
    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.warn("闲置会话扫描失败，下次扫描继续", e);
        }
    }

    /**
     * 扫描并关闭闲置会话。
     * <p>
     * {@code idle-timeout ≤ 0} 时直接返回——{@link #start()} 已经不会排这条扫描，但本方法
     * （以及 {@link #shouldReap}）是包私有接缝，测试与将来的调度入口都可能直接调它，
     * 因此「不回收」这道判断必须落在真正做决定的地方。
     *
     * @return 本次关闭的会话数
     */
    int sweep() {
        if (idleTimeoutMillis == REAP_DISABLED) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - idleTimeoutMillis;
        int closed = 0;
        Collection<Session> all = sessions.all();
        for (Session session : all) {
            if (shouldReap(session, deadline)) {
                closeQuietly(session.getSessionId());
                closed++;
            }
        }
        if (closed > 0) {
            LOG.info("已回收 {} 个闲置会话", closed);
        }
        return closed;
    }

    /**
     * 判断一个会话是否该被回收。
     *
     * @param session  会话运行态
     * @param deadline 闲置门槛时刻：最后活动早于它才算闲置
     * @return 该回收返回 {@code true}
     */
    private boolean shouldReap(Session session, long deadline) {
        if (owners.ownerOf(session.getSessionId()).isEmpty()) {
            // 没有归属者的会话不是本层建的，不越权处置
            return false;
        }
        if (turns.isRunning(session.getSessionId())) {
            // 长 LLM 调用在新消息落库前不会更新 updatedAt，只看时间戳会关掉正在跑的回合
            return false;
        }
        return session.getUpdatedAt() < deadline;
    }

    /**
     * 关闭一个会话并解绑，失败只记日志。
     *
     * @param sessionId 会话标识
     */
    private void closeQuietly(String sessionId) {
        try {
            sessions.close(sessionId, SessionBeforeCloseRequest.Reason.INTERNAL);
            owners.unbind(sessionId);
        } catch (RuntimeException e) {
            // 单个会话关不掉不该让整轮扫描停下（后面的会话还得回收）
            LOG.warn("回收会话失败，跳过: sessionId={}", sessionId, e);
        }
    }

    /**
     * 回收线程的工厂：守护线程，不阻止 JVM 退出。
     *
     * @author zcd
     */
    private static final class ReaperThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jellyfish-session-reaper");
            thread.setDaemon(true);
            return thread;
        }
    }
}
