package zcd.jellyfish.spring.boot.user;

import java.util.List;

/**
 * 会话归属存储：回答「这个会话是谁的」。
 * <p>
 * <b>它是本层最根本的一件事</b>：内核<b>没有「用户」这一维</b>——{@code Session} 上没有 owner 字段、
 * {@code SessionManager.all()} 返回全进程全部会话、{@code SessionSnapshot} 里也没有归属信息。
 * 因此「谁是谁的会话」只能在接入方这一侧记账，而记账的形态很依赖业务（内存 / 关系库 / 缓存 / 已有的
 * 用户体系），所以这里定义成 SPI：接入方实现它就能与自己的存储对齐。
 * <p>
 * 默认实现是 {@link InMemorySessionOwnerStore}，它与会话本身的生命周期一致（内核的会话默认也在内存里，
 * 除非装了持久化插件）。<b>若接入方给会话加了持久化，归属也必须一起持久化</b>——
 * 否则重启后会话还在、归属没了，那些会话就变成了谁也访问不到的孤儿。
 * <p>
 * <b>实现要求</b>：
 * <ul>
 *     <li>线程安全：回合在 {code react} 线程上跑，HTTP 请求在容器线程上跑，两边都会读它；</li>
 *     <li>{@link #bind} 与 {@link #unbind} 是幂等的：重复绑定同一对 (userId, sessionId) 不应报错，
 *     解绑一个不存在的会话也不应报错（并发关闭 / 重复删除都会走到这里）；</li>
 *     <li>不要在这里做权限判定之外的业务：它只回答归属，不回答「这个用户能不能用这个工具」。</li>
 * </ul>
 *
 * @author zcd
 */
public interface SessionOwnerStore {

    /**
     * 记下会话的归属。
     *
     * @param userId    归属者标识，不可为空白
     * @param sessionId 会话标识，不可为空白
     */
    void bind(String userId, String sessionId);

    /**
     * 解除会话归属。
     *
     * @param sessionId 会话标识，不可为空白
     */
    void unbind(String sessionId);

    /**
     * 判断会话是否属于该用户。
     *
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @return 属于返回 {@code true}
     */
    boolean owns(String userId, String sessionId);

    /**
     * 列出某用户的全部会话标识。
     *
     * @param userId 归属者标识
     * @return 会话标识列表；没有时为空列表而非 {@code null}
     */
    List<String> sessionsOf(String userId);

    /**
     * 统计某用户持有的会话数。
     * <p>
     * 供配额判定使用。默认按 {@link #sessionsOf} 的长度算，存储支持更快的计数时应当覆盖它。
     *
     * @param userId 归属者标识
     * @return 会话数
     */
    default int countOf(String userId) {
        return sessionsOf(userId).size();
    }

    /**
     * 查会话的归属者。
     *
     * @param sessionId 会话标识
     * @return 归属者标识；没有记录时为空串
     */
    String ownerOf(String sessionId);
}
