package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserQuotaGuard} 的单元测试。
 * <p>
 * 两件最要紧的事：额度不能被同一用户超发，也不能因为归还次数不对而凭空变多或永久变少。
 *
 * @author zcd
 */
@DisplayName("UserQuotaGuard 配额闸门")
class UserQuotaGuardTest {

    /**
     * 构造配额闸门。
     *
     * @param maxTurns    每用户并发回合上限
     * @param maxSessions 每用户会话数上限
     * @return 闸门
     */
    private UserQuotaGuard guard(int maxTurns, int maxSessions) {
        JellyfishUserProperties properties = new JellyfishUserProperties();
        properties.setMaxInflightTurnsPerUser(maxTurns);
        properties.setMaxSessionsPerUser(maxSessions);
        return new UserQuotaGuard(properties);
    }

    /**
     * 构造带指定追踪上限的闸门。
     *
     * @param maxTurns        每用户并发回合上限
     * @param maxSessions     每用户会话数上限
     * @param maxTrackedUsers 追踪的用户数上限
     * @return 配额闸门
     */
    private static UserQuotaGuard guard(int maxTurns, int maxSessions, int maxTrackedUsers) {
        JellyfishUserProperties properties = new JellyfishUserProperties();
        properties.setMaxInflightTurnsPerUser(maxTurns);
        properties.setMaxSessionsPerUser(maxSessions);
        return new UserQuotaGuard(properties, maxTrackedUsers);
    }

    @Test
    @DisplayName("额度用尽后占不到，归还之后又能占到")
    void tryAcquireTurn_should_stop_at_limit_and_recover_after_release_when_configured() {
        // Given
        UserQuotaGuard quota = guard(2, 10);

        // When / Then
        assertTrue(quota.tryAcquireTurn("alice"));
        assertTrue(quota.tryAcquireTurn("alice"));
        assertFalse(quota.tryAcquireTurn("alice"), "第三个不该占到");

        quota.releaseTurn("alice");
        assertTrue(quota.tryAcquireTurn("alice"), "归还之后应当又能占到");
    }

    @Test
    @DisplayName("额度按用户分开：一个人用满不影响别人")
    void tryAcquireTurn_should_not_affect_other_users_when_one_is_saturated() {
        // Given
        UserQuotaGuard quota = guard(1, 10);

        // When
        assertTrue(quota.tryAcquireTurn("alice"));

        // Then
        assertFalse(quota.tryAcquireTurn("alice"));
        assertTrue(quota.tryAcquireTurn("bob"), "bob 的额度与 alice 无关");
    }

    @Test
    @DisplayName("上限为 0 或负数表示不限")
    void tryAcquireTurn_should_always_succeed_when_limit_is_not_positive() {
        // Given
        UserQuotaGuard unlimited = guard(0, 0);

        // When / Then
        for (int i = 0; i < 100; i++) {
            assertTrue(unlimited.tryAcquireTurn("alice"));
        }
        unlimited.releaseTurn("alice");
        unlimited.requireSessionSlot("alice", 1_000_000);
    }

    @Test
    @DisplayName("会话数到上限时拒绝，未到则放行")
    void requireSessionSlot_should_reject_when_limit_reached() {
        // Given
        UserQuotaGuard quota = guard(1, 3);

        // When / Then
        quota.requireSessionSlot("alice", 0);
        quota.requireSessionSlot("alice", 2);
        UserQuotaExceededException thrown = assertThrows(UserQuotaExceededException.class,
                () -> quota.requireSessionSlot("alice", 3));
        assertEquals("alice", thrown.getUserId());
    }

    @Test
    @DisplayName("标识为空的调用归入同一个桶，不会每次各建一个额度")
    void tryAcquireTurn_should_share_one_bucket_when_user_id_is_blank() {
        // Given
        UserQuotaGuard quota = guard(1, 10);

        // When
        assertTrue(quota.tryAcquireTurn(null));

        // Then：空标识与空串共用一份额度（否则每个空标识都能各拿一份，等于没有配额）
        assertFalse(quota.tryAcquireTurn(""));
    }

    @Test
    @DisplayName("归还多于占用时额度不会凭空变多到失控")
    void tryAcquireTurn_should_reflect_single_release_when_releasing_once() {
        // Given
        UserQuotaGuard quota = guard(1, 10);
        assertTrue(quota.tryAcquireTurn("alice"));

        // When：恰好归还一次（调用方用一次性标志保证），随后再用
        quota.releaseTurn("alice");

        // Then
        assertTrue(quota.tryAcquireTurn("alice"));
        assertFalse(quota.tryAcquireTurn("alice"), "仍然只有一份额度");
    }

    @Test
    @DisplayName("从未占过额度的标识归还时不给自己挣额度")
    void releaseTurn_should_not_grant_quota_when_nothing_was_acquired() {
        // Given：一个从未占过额度的标识
        UserQuotaGuard quota = guard(1, 10);

        // When：凭空归还（正常路径不会这样，但 map 是懒建的，不能让它成为加额度的入口）
        quota.releaseTurn("never-seen");

        // Then：它不该因此获得额度
        quota.releaseTurn("never-seen");
        assertTrue(quota.tryAcquireTurn("never-seen"), "第一次占应当成功");
        assertFalse(quota.tryAcquireTurn("never-seen"), "仍然只有一份额度，归还没有把它撑大");
    }

    @Test
    @DisplayName("追踪上限：标识数达上限后新标识一律占不到额度（fail-closed）")
    void tryAcquireTurn_should_denyNewUser_when_trackedUsersReachLimit() {
        // Given：把上限设为 2 并填满（真实上限填不满，用包级重载注入）
        UserQuotaGuard quota = guard(1, 10, 2);
        assertTrue(quota.tryAcquireTurn("user-0"));
        assertTrue(quota.tryAcquireTurn("user-1"));

        // When / Then：已有标识照常占得到（它们的额度互不影响）
        quota.releaseTurn("user-0");
        assertTrue(quota.tryAcquireTurn("user-0"), "已追踪的标识不受上限影响");

        // Then：新标识被拒——这是「调用方标识无界」时的兜底，方向必须是 fail-closed
        assertFalse(quota.tryAcquireTurn("brand-new"), "达到上限后新标识不给额度");

        // Then：被拒的标识不该因为 release 而挣到额度
        quota.releaseTurn("brand-new");
        assertFalse(quota.tryAcquireTurn("brand-new"), "归还一个不存在的额度不能凭空造出额度");
    }
}
