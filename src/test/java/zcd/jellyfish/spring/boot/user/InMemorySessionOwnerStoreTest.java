package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemorySessionOwnerStore} 的单元测试。
 * <p>
 * 重点在两个方向相反的表是否始终一致——「按用户列得出、按 id 却说不属于你」这类自相矛盾的状态，
 * 在多用户下会直接变成越权或「会话凭空消失」。
 *
 * @author zcd
 */
@DisplayName("InMemorySessionOwnerStore 归属存储")
class InMemorySessionOwnerStoreTest {

    /** 被测对象。 */
    private InMemorySessionOwnerStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionOwnerStore();
    }

    @Test
    @DisplayName("绑定后两个方向都能查到")
    void bind_should_be_visible_from_both_directions_when_bound() {
        // When
        store.bind("alice", "s1");

        // Then
        assertTrue(store.owns("alice", "s1"));
        assertEquals(java.util.Arrays.asList("s1"), store.sessionsOf("alice"));
        assertEquals("alice", store.ownerOf("s1"));
        assertEquals(1, store.countOf("alice"));
    }

    @Test
    @DisplayName("别人的会话一律不认")
    void owns_should_reject_when_user_is_not_the_owner() {
        // Given
        store.bind("alice", "s1");

        // When / Then
        assertFalse(store.owns("bob", "s1"));
        assertTrue(store.sessionsOf("bob").isEmpty());
        assertEquals(0, store.countOf("bob"));
    }

    @Test
    @DisplayName("未绑定的会话谁也不认")
    void owns_should_reject_when_session_is_unknown() {
        // When / Then
        assertFalse(store.owns("alice", "unknown"));
        assertFalse(store.owns("alice", ""));
        assertFalse(store.owns("", "s1"));
        assertFalse(store.owns(null, "s1"));
        assertFalse(store.owns("alice", null));
        assertEquals("", store.ownerOf("unknown"));
    }

    @Test
    @DisplayName("解绑后两个方向一起清掉")
    void unbind_should_clear_both_directions_when_called() {
        // Given
        store.bind("alice", "s1");

        // When
        store.unbind("s1");

        // Then
        assertFalse(store.owns("alice", "s1"));
        assertTrue(store.sessionsOf("alice").isEmpty());
        assertEquals("", store.ownerOf("s1"));
        assertEquals(0, store.countOf("alice"));
    }

    @Test
    @DisplayName("解绑不存在的会话不报错（并发删除会走到这里）")
    void unbind_should_be_silent_when_session_is_unknown() {
        // When / Then
        store.unbind("unknown");
        store.unbind("");
        store.unbind(null);
    }

    @Test
    @DisplayName("重复绑定同一对不产生重复条目")
    void bind_should_be_idempotent_when_called_twice() {
        // When
        store.bind("alice", "s1");
        store.bind("alice", "s1");

        // Then
        assertEquals(1, store.countOf("alice"));
        assertEquals(1, store.sessionsOf("alice").size());
    }

    @Test
    @DisplayName("改绑给另一个用户时，旧用户的索引也必须清掉")
    void bind_should_move_session_when_owner_changes() {
        // Given
        store.bind("alice", "s1");

        // When
        store.bind("bob", "s1");

        // Then：两个方向必须一致——旧用户若还列得出这个会话，他就能拿它去调后续接口
        assertFalse(store.owns("alice", "s1"));
        assertTrue(store.sessionsOf("alice").isEmpty());
        assertTrue(store.owns("bob", "s1"));
        assertEquals("bob", store.ownerOf("s1"));
    }

    @Test
    @DisplayName("一个用户可以持有多个会话，解绑其中一个不影响其它")
    void unbind_should_keep_other_sessions_when_one_is_removed() {
        // Given
        store.bind("alice", "s1");
        store.bind("alice", "s2");

        // When
        store.unbind("s1");

        // Then
        assertEquals(1, store.countOf("alice"));
        assertEquals(java.util.Arrays.asList("s2"), store.sessionsOf("alice"));
        assertEquals("", store.ownerOf("s1"));
        assertEquals("alice", store.ownerOf("s2"));
    }

    @Test
    @DisplayName("标识为空白时绑定被忽略，不产生谁也查不到的记录")
    void bind_should_ignore_blank_identifiers_when_called() {
        // When
        store.bind("", "s1");
        store.bind("alice", " ");
        store.bind(null, "s1");

        // Then
        assertEquals(0, store.countOf(""));
        assertEquals(0, store.countOf("alice"));
        assertEquals("", store.ownerOf("s1"));
    }

    @Test
    @DisplayName("列举结果是只读快照，调用方改不动内部状态")
    void sessionsOf_should_return_snapshot_when_called() {
        // Given
        store.bind("alice", "s1");

        // When
        java.util.List<String> listed = store.sessionsOf("alice");

        // Then
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> listed.add("s2"));
        store.bind("alice", "s2");
        assertEquals(1, listed.size(), "快照不应随后续绑定的变化");
    }
}
