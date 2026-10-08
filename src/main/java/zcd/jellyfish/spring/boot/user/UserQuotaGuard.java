package zcd.jellyfish.spring.boot.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <b>追踪的用户数有硬上限</b>：{@link #permitsByUser} 会为每个出现过的用户留一条记录，生命期与进程相同。
 * 真实服务里用户数是有限的，因此不做清理；但「调用方标识」是可以被伪造或误用的（把请求 id 当用户、
 * 每次请求换个标识），那样这个映射就成了泄漏点。因此加一个常量上限兜底：到达上限后<b>新的标识一律
 * 占不到额度</b>（fail-closed，并记一条 WARN）。上限取常量而不是配置项——它是兜底，不是需要按场景
 * 调优的旋钮；真要支持更多用户，那是「标识本身有界」的前提不成立，该先修调用方。
 *
 * @author zcd
 */
public class UserQuotaGuard {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(UserQuotaGuard.class);

    /**
     * 追踪的用户数上限。
     * <p>
     * 远大于任何真实部署的用户数，因此正常使用永远碰不到；它的作用只是让「标识无界」这种误用
     * 不会把进程内存吃光。
     */
    static final int MAX_TRACKED_USERS = 65536;

    /** 每用户并发回合上限，{@code <= 0} 表示不限。 */
    private final int maxInflightTurnsPerUser;

    /** 每用户会话数上限，{@code <= 0} 表示不限。 */
    private final int maxSessionsPerUser;

    /** 用户 → 回合额度的信号量。 */
    private final Map<String, Semaphore> permitsByUser = new ConcurrentHashMap<String, Semaphore>();

    /** 追踪的用户数上限，正常构造取 {@link #MAX_TRACKED_USERS}。 */
    private final int maxTrackedUsers;

    /**
     * 构造配额闸门。
     *
     * @param properties 配置，不可为 {@code null}
     */
    public UserQuotaGuard(JellyfishUserProperties properties) {
        this(properties, MAX_TRACKED_USERS);
    }

    /**
     * 按指定追踪上限构造，供测试钉住「到上限后新标识被拒」这条判据。
     * <p>
     * 真实上限太大、填不满，这个重载是唯一的测试入口；与 {@code ActionQueue(int capacity)} 同一做法。
     *
     * @param properties      配置，不可为 {@code null}
     * @param maxTrackedUsers 追踪的用户数上限，必须为正数
     */
    UserQuotaGuard(JellyfishUserProperties properties, int maxTrackedUsers) {
        this.maxInflightTurnsPerUser = properties.getMaxInflightTurnsPerUser();
        this.maxSessionsPerUser = properties.getMaxSessionsPerUser();
        this.maxTrackedUsers = maxTrackedUsers;
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
        Semaphore permits = permitsFor(userId);
        return permits != null && permits.tryAcquire();
    }

    /**
     * 归还一个回合额度。
     * <p>
     * <b>必须与 {@link #tryAcquireTurn} 成对且恰好一次</b>：多还一次会让额度凭空变多（信号量会不断累加），
     * 少还一次会让该用户永久少一个额度、最终再也起不了回合。调用方（{@code JellyfishUserFacade}）
     * 用「终态事件 + 一次性标志」保证这一点。
     * <p>
     * <b>没有记录时不归还</b>：该用户的占用从未成功（例如被标识数上限拒过），凭空
     * {@code release()} 会给一个不存在的额度加一，后续调用就能超发。
     *
     * @param userId 请求者标识
     */
    public void releaseTurn(String userId) {
        if (maxInflightTurnsPerUser <= 0) {
            return;
        }
        Semaphore permits = permitsByUser.get(keyOf(userId));
        if (permits != null) {
            permits.release();
        }
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
        String key = keyOf(userId);
        Semaphore existing = permitsByUser.get(key);
        if (existing != null) {
            return existing;
        }
        Semaphore created = new Semaphore(maxInflightTurnsPerUser);
        Semaphore raced = permitsByUser.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    /**
     * 取某用户的额度信号量；到达追踪上限且该用户是新的时返回 {@code null}。
     * <p>
     * 返回 {@code null} 而不是「一个不可获取的信号量」：后者会被 {@code release()} 加回许可，
     * 于是那个被拒的标识反而给自己挣来一个额度。
     *
     * @param userId 请求者标识
     * @return 信号量；标识数已达上限且该用户尚不存在时返回 {@code null}
     */
    private Semaphore permitsFor(String userId) {
        Semaphore existing = permitsByUser.get(keyOf(userId));
        if (existing != null) {
            return existing;
        }
        if (permitsByUser.size() >= maxTrackedUsers) {
            LOG.warn("追踪的用户数已达上限 {}，新标识一律不给额度（fail-closed）："
                    + "这说明调用方标识无界，请检查是不是把请求 id 当成了 userId", maxTrackedUsers);
            return null;
        }
        return semaphoreOf(userId);
    }

    /**
     * 归一化用户标识：空白归入一个固定桶。
     *
     * @param userId 请求者标识，可为 {@code null}
     * @return 映射键，保证非 {@code null}
     */
    private static String keyOf(String userId) {
        return StringUtils.hasText(userId) ? userId : "";
    }
}
