package zcd.jellyfish.spring.boot.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把「回合终态时归还额度并退订」绑到业务回调上的包装。
 * <p>
 * <b>归还必须恰好一次</b>：多还一次会让该用户的额度凭空变多（信号量会持续累加），少还一次会让它
 * 永久少一个额度。因此用一次性标志把「终态事件」与「提交失败」这两条路都收敛到同一个入口，
 * 谁先到谁生效。
 * <p>
 * <b>退订也必须恰好一次，而且不能漏</b>：{@link #attach} 是「订阅建好之后再登记」的两步动作，
 * 而终态事件可能落在两步之间（同一会话上已有在途回合时，它的终态正是在那一刻推进来的）。
 * 那时 {@link #finish()} 已经跑过、看到的句柄还是 {@code null}，于是跳过退订——监听器永久留在
 * {@code ShellStreams} 上：内存泄漏，且业务回调在「已结束」之后继续被调用。因此 {@code attach}
 * 赋值之后要再查一次。
 * <p>
 * 独立成包级类而不是外围门面的内部类：只有这样测试才能直接构造它、把那个窗口<b>确定性地</b>
 * 摆出来（先 {@code finish} 再 {@code attach}），而不必去碰运气复现并发。
 *
 * @author zcd
 */
final class QuotaReleasingListener implements ShellTurnListener {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(QuotaReleasingListener.class);

    /** 请求者标识。 */
    private final String userId;

    /** 业务侧的回调。 */
    private final ShellTurnListener delegate;

    /** 额度闸门。 */
    private final UserQuotaGuard quota;

    /** 是否已经收尾（归还额度 + 退订）。 */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    /** 可靠 lane 的订阅句柄，订阅成功后才非 {@code null}。 */
    private volatile Subscription subscription;

    /**
     * 构造包装。
     *
     * @param userId   请求者标识
     * @param delegate 业务侧回调，不可为 {@code null}
     * @param quota    额度闸门，不可为 {@code null}
     */
    QuotaReleasingListener(String userId, ShellTurnListener delegate, UserQuotaGuard quota) {
        this.userId = userId;
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.quota = Objects.requireNonNull(quota, "quota must not be null");
    }

    @Override
    public void onTurnEvent(ShellTurnEvent event) {
        try {
            delegate.onTurnEvent(event);
        } catch (RuntimeException e) {
            // 业务回调抛错不该把内核的事件派发弄挂（订阅者在 react 线程上被同步调用）。
            // 记下来、吞掉，让内核继续推后面的事件
            LOG.warn("回合事件回调抛错，已忽略: sessionId={} kind={}",
                    event.getSessionId(), event.getKind(), e);
        } finally {
            if (event.isTerminal()) {
                finish();
            }
        }
    }

    /**
     * 登记订阅句柄；若收尾已经发生过，立刻退订。
     *
     * @param created 订阅句柄，不可为 {@code null}
     */
    void attach(Subscription created) {
        this.subscription = Objects.requireNonNull(created, "subscription must not be null");
        if (finished.get()) {
            // 收尾已经跑过（终态落在「订阅建好」与「登记」之间），它当时看不到这个句柄
            created.close();
        }
    }

    /**
     * 收尾：退订并归还额度，恰好一次。
     */
    void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Subscription current = this.subscription;
        if (current != null) {
            current.close();
        }
        quota.releaseTurn(userId);
    }
}
