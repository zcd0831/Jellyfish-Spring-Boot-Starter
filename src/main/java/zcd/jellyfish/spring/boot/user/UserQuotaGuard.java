package zcd.jellyfish.spring.boot.user;

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 每用户配额：并发回合数与会话数。
 * <p>
 * <b>它补的是内核的一处结构性缺口</b>：内核的并发额度全是<b>进程级</b>的——{@code react} 线程池
 * （8 线程 + 128 等待位）、子代理并发许可、压缩线程池各有一份全局额度，且没有任何一处按调用者分账。
 * 于是一个用户只要并起几十个会话就能把整池占满。内核不认识「用户」，这道闸门只能由本层加。
 * <p>
 * <b>并发回合用信号量而不是「读一个计数再比较」</b>：后者在并发下必然超发。信号量把「占用」变成一次
 * 原子操作，占不到就是占不到。<b>配额为 {@code <= 0} 时整套闸门退化为不限制</b>——
 * 但要注意这不是「更宽松的默认」，而是把上面那个缺口重新暴露出来。
 * <p>
 * <b>已知代价</b>：{@link #permitsByUser} 会为每个出现过的用户留一条记录，生命期与进程相同。
 * 真实服务里用户数是有限的，因此不做清理；若接入方有无限多的调用者标识（例如把请求 id 当用户），
 * 这个映射会成为泄漏点——那种用法本身也不该走这条路径。
 *
 * @author zcd
 */
public class UserQuotaGuard {

    /** 每用户并发回合上限，{@code <= 0} 表示不限。 */
    private final int maxInflightTurnsPerUser;

    /** 每用户会话数上限，{@code <= 0} 表示不限。 */
    private final int maxSessionsPerUser;

    /** 用户 → 回合额度的信号量。 */
    private final Map<String, Semaphore> permitsByUser = new ConcurrentHashMap<String, Semaphore>();

    /**
     * 构造配额闸门。
     *
     * @param properties 配置，不可为 {@code null}
     */
    public UserQuotaGuard(JellyfishUserProperties properties) {
        this.maxInflightTurnsPerUser = properties.getMaxInflightTurnsPerUser();
        this.maxSessionsPerUser = properties.getMaxSessionsPerUser();
    }

    /**
     * 尝试占用一个回合额度。
     * <p>
     * 不阻塞：占不到说明该用户已经在跑上限那么多回合，此时排队等待只会把容器线程也一起堵住，
     * 让调用方立刻拿到明确答复更有用。
     *
     * @param userId 请求者标识
     * @return 占到返回 {@code true}
     */
    public boolean tryAcquireTurn(String userId) {
        if (maxInflightTurnsPerUser <= 0) {
            return true;
        }
        return semaphoreOf(userId).tryAcquire();
    }

    /**
     * 归还一个回合额度。
     * <p>
     * <b>必须与 {@link #tryAcquireTurn} 成对且恰好一次</b>：多还一次会让额度凭空变多（信号量会不断累加），
     * 少还一次会让该用户永久少一个额度、最终再也起不了回合。调用方（{@code JellyfishUserFacade}）
     * 用「终态事件 + 一次性标志」保证这一点。
     *
     * @param userId 请求者标识
     */
    public void releaseTurn(String userId) {
        if (maxInflightTurnsPerUser <= 0) {
            return;
        }
        semaphoreOf(userId).release();
    }

    /**
     * 检查是否还能再建一个会话。
     *
     * @param userId       请求者标识
     * @param currentCount 该用户当前持有的会话数
     * @throws UserQuotaExceededException 已到上限时抛出
     */
    public void requireSessionSlot(String userId, int currentCount) {
        if (maxSessionsPerUser <= 0 || currentCount < maxSessionsPerUser) {
            return;
        }
        throw new UserQuotaExceededException(userId,
                "会话数已达上限（" + maxSessionsPerUser + "），请先删除不用的会话");
    }

    /**
     * 获取某用户的回合额度信号量。
     *
     * @param userId 请求者标识
     * @return 信号量，保证非 {@code null}
     */
    private Semaphore semaphoreOf(String userId) {
        // 标识为空时归入一个固定桶：宁可让这类调用互相争，也不要每个空标识各建一个信号量
        String key = StringUtils.hasText(userId) ? userId : "";
        Semaphore existing = permitsByUser.get(key);
        if (existing != null) {
            return existing;
        }
        Semaphore created = new Semaphore(maxInflightTurnsPerUser);
        Semaphore raced = permitsByUser.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }
}
