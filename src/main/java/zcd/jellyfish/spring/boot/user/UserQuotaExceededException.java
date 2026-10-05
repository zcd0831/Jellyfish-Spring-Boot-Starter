package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.api.JellyfishException;

/**
 * 超出每用户配额时抛出：并发回合数或会话数到了上限。
 * <p>
 * <b>为什么必须有这道闸门</b>：内核的并发能力是<b>全进程共享</b>的——{@code react} 线程池一共 8 条线程
 * 加 128 个等待位，子代理并发许可与压缩线程池也各有一份全局额度。一个用户只要并起几十个会话，
 * 就能把整个池占满，其余用户的请求全部排队或直接被拒。
 * 内核不提供按调用者的配额（它连「用户」都不认识），因此这道闸门只能由本层提供。
 * <p>
 * 它与内核已有的「同一会话同时只有一个回合」是两件事：那条是会话级的互斥，这条是用户级的额度。
 *
 * @author zcd
 */
public class UserQuotaExceededException extends JellyfishException {

    /** 请求者标识（仅用于日志）。 */
    private final String userId;

    /**
     * 构造异常。
     *
     * @param userId 请求者标识
     * @param reason 超限原因，写成一句给人看的话
     */
    public UserQuotaExceededException(String userId, String reason) {
        super(reason);
        this.userId = userId;
    }

    /**
     * 获取请求者标识。
     *
     * @return 请求者标识
     */
    public String getUserId() {
        return userId;
    }
}
