package zcd.jellyfish.spring.boot.user;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link SessionOwnerStore} 的默认实现：两张互相索引的进程内映射。
 * <p>
 * <b>为什么可以默认用内存</b>：内核的会话默认也在内存里（一个进程内的 {@code ConcurrentHashMap}，
 * 只有装了持久化插件才会落盘）。因此「归属与会话同生命周期」是一致的——两者一起在重启时消失。
 * <b>一旦接入方给会话加了持久化，就必须换成持久化的归属存储</b>，理由见
 * {@link SessionOwnerStore} 的类注释。
 * <p>
 * <b>为什么维护两张方向相反的表</b>：两个方向都会被高频问到——校验时按 (userId, sessionId) 问、
 * 列举「我的会话」时按 userId 问、清理时按 sessionId 问。只存一个方向就要线性扫描，而这条路径在
 * 每个 HTTP 请求上都会走。
 * <p>
 * <b>两张表的写入必须一致</b>：{@link #bind} 与 {@link #unbind} 各自在同一把锁里同时改两张表，
 * 否则会出现「按用户列得出、按 id 却说不属于你」这种自相矛盾的状态。单张表用并发容器是不够的，
 * 一致性要靠这把锁来保证。
 *
 * @author zcd
 */
public class InMemorySessionOwnerStore implements SessionOwnerStore {

    /** 用户 → 他的会话集合。用 {@link CopyOnWriteArrayList} 是为了让「列举」这条读路径不加锁。 */
    private final Map<String, CopyOnWriteArrayList<String>> sessionsByUser =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<String>>();

    /** 会话 → 归属者。 */
    private final Map<String, String> ownerBySession = new ConcurrentHashMap<String, String>();

    /** 保证两张表一起改的锁：只有写入路径会抢它，读路径不加锁。 */
    private final Object writeLock = new Object();

    @Override
    public void bind(String userId, String sessionId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
            return;
        }
        synchronized (writeLock) {
            String previous = ownerBySession.put(sessionId, userId);
            if (previous != null && !previous.equals(userId)) {
                // 重新绑定到别人：先把旧的索引清掉，否则旧用户那边会一直列出这个会话
                CopyOnWriteArrayList<String> oldList = sessionsByUser.get(previous);
                if (oldList != null) {
                    oldList.remove(sessionId);
                }
            }
            CopyOnWriteArrayList<String> list = sessionsByUser.get(userId);
            if (list == null) {
                list = new CopyOnWriteArrayList<String>();
                CopyOnWriteArrayList<String> existing = sessionsByUser.putIfAbsent(userId, list);
                list = existing == null ? list : existing;
            }
            if (!list.contains(sessionId)) {
                list.add(sessionId);
            }
        }
    }

    @Override
    public void unbind(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        synchronized (writeLock) {
            String owner = ownerBySession.remove(sessionId);
            if (owner == null) {
                return;
            }
            CopyOnWriteArrayList<String> list = sessionsByUser.get(owner);
            if (list != null) {
                list.remove(sessionId);
                if (list.isEmpty()) {
                    sessionsByUser.remove(owner, list);
                }
            }
        }
    }

    @Override
    public boolean owns(String userId, String sessionId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
            return false;
        }
        // 直接查反向索引：一次哈希查找，而不是「列出我的会话再 contains」那种 O(n)
        return userId.equals(ownerBySession.get(sessionId));
    }

    @Override
    public List<String> sessionsOf(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Collections.emptyList();
        }
        CopyOnWriteArrayList<String> list = sessionsByUser.get(userId);
        return list == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(list));
    }

    @Override
    public int countOf(String userId) {
        if (!StringUtils.hasText(userId)) {
            return 0;
        }
        CopyOnWriteArrayList<String> list = sessionsByUser.get(userId);
        return list == null ? 0 : list.size();
    }

    @Override
    public String ownerOf(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return "";
        }
        String owner = ownerBySession.get(sessionId);
        return owner == null ? "" : owner;
    }
}
