package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuotaReleasingListener} 的单元测试。
 * <p>
 * 核心是两条「恰好一次 + 一次都不能漏」：额度归还恰好一次（多还额度会凭空变多，少还永久少一个），
 * 退订恰好一次且<b>不能漏</b>——终态可能落在「订阅建好」与「登记句柄」之间那个窗口里，
 * 那正是漏掉退订、把监听器永久留在 {@code ShellStreams} 上的成因。
 * <p>
 * 这个窗口能<b>确定性地</b>摆出来（先 {@code finish()} 再 {@code attach(...)}），不必靠并发碰运气——
 * 那正是把它从门面的内部类里抽出来的原因。
 *
 * @author zcd
 */
@DisplayName("QuotaReleasingListener 收尾包装")
class QuotaReleasingListenerTest {

    @Test
    @DisplayName("终态事件收尾：退订一次、归还额度一次")
    void onTurnEvent_should_finishOnce_when_terminalEventArrives() {
        // Given
        CountingSubscription subscription = new CountingSubscription();
        UserQuotaGuard quota = quota(1);
        assertTrue(quota.tryAcquireTurn("alice"));
        QuotaReleasingListener listener = new QuotaReleasingListener("alice", event -> { }, quota);
        listener.attach(subscription);

        // When
        listener.onTurnEvent(terminal());

        // Then
        assertEquals(1, subscription.closed.get());
        // 额度确实还了：还能再占到一次（配额为 1）
        assertTrue(quota.tryAcquireTurn("alice"));
    }

    @Test
    @DisplayName("终态落在「订阅建好」与「登记句柄」之间：登记时必须补退订，不能漏")
    void attach_should_closeSubscription_when_finishAlreadyHappened() {
        // Given：收尾先发生（同一会话上已有在途回合时，它的终态正是在这个窗口里推进来的）
        UserQuotaGuard quota = quota(1);
        QuotaReleasingListener listener = new QuotaReleasingListener("alice", event -> { }, quota);
        listener.finish();

        // When：订阅之后才登记句柄
        CountingSubscription subscription = new CountingSubscription();
        listener.attach(subscription);

        // Then：必须补一次退订。少了它，监听器会永久留在 ShellStreams 上——内存泄漏，
        // 且业务回调在「已结束」之后继续被调用
        assertEquals(1, subscription.closed.get(), "收尾已发生，登记时必须立刻退订");
    }

    @Test
    @DisplayName("业务回调抛错：吞掉、不阻断内核派发，收尾照常")
    void onTurnEvent_should_finish_when_delegateThrows() {
        // Given
        CountingSubscription subscription = new CountingSubscription();
        UserQuotaGuard quota = quota(1);
        assertTrue(quota.tryAcquireTurn("alice"));
        ShellTurnListener throwing = event -> {
            throw new IllegalStateException("业务回调坏了");
        };
        QuotaReleasingListener listener = new QuotaReleasingListener("alice", throwing, quota);
        listener.attach(subscription);

        // When
        listener.onTurnEvent(terminal());

        // Then
        assertEquals(1, subscription.closed.get());
        assertTrue(quota.tryAcquireTurn("alice"), "额度仍然被归还了");
    }

    @Test
    @DisplayName("收尾幂等：重复收尾不会重复退订、也不会多还额度")
    void finish_should_beIdempotent() {
        // Given
        CountingSubscription subscription = new CountingSubscription();
        UserQuotaGuard quota = quota(1);
        assertTrue(quota.tryAcquireTurn("alice"));
        QuotaReleasingListener listener = new QuotaReleasingListener("alice", event -> { }, quota);
        listener.attach(subscription);

        // When：收尾两次（终态与提交失败两条路都可能走到）
        listener.finish();
        listener.finish();

        // Then：额度只有一份，多还的话这里会连着占到两次
        assertEquals(1, subscription.closed.get());
        assertTrue(quota.tryAcquireTurn("alice"));
        assertFalse(quota.tryAcquireTurn("alice"), "额度不该被多还而变多");
    }

    @Test
    @DisplayName("非终态事件不收尾")
    void onTurnEvent_should_notFinish_when_eventIsNotTerminal() {
        // Given
        CountingSubscription subscription = new CountingSubscription();
        UserQuotaGuard quota = quota(1);
        assertTrue(quota.tryAcquireTurn("alice"));
        QuotaReleasingListener listener = new QuotaReleasingListener("alice", event -> { }, quota);
        listener.attach(subscription);

        // When：STARTED 这类过程事件
        listener.onTurnEvent(ShellTurnEvent.started("s1", "t1"));

        // Then
        assertEquals(0, subscription.closed.get());
        assertFalse(quota.tryAcquireTurn("alice"), "额度还没还，仍然占着");
    }

    /**
     * 构造配额闸门。
     *
     * @param maxTurns 每用户并发回合上限
     * @return 闸门
     */
    private static UserQuotaGuard quota(int maxTurns) {
        JellyfishUserProperties properties = new JellyfishUserProperties();
        properties.setMaxInflightTurnsPerUser(maxTurns);
        properties.setMaxSessionsPerUser(10);
        return new UserQuotaGuard(properties);
    }

    /**
     * 造一个终态事件。
     *
     * @return 终态事件
     */
    private static ShellTurnEvent terminal() {
        return ShellTurnEvent.completed("s1", "t1", "答完了", 1, false);
    }

    /**
     * 记退订次数的订阅句柄。
     */
    private static final class CountingSubscription implements Subscription {

        /** 退订次数。 */
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
