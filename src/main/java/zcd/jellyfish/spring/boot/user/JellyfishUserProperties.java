package zcd.jellyfish.spring.boot.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 多用户护栏的配置项（前缀 {@code jellyfish.user}）。
 * <p>
 * 这里的每一项都在补内核<b>没有</b>的能力：内核不认识「用户」，它的并发额度全是进程级共享的。
 * 因此这些缺省值都取得比较保守——宁可让接入方按自己的容量调大，也不要让一个用户就能把整池占满。
 *
 * @author zcd
 */
@ConfigurationProperties(prefix = "jellyfish.user")
public class JellyfishUserProperties {

    /** 是否启用多用户门面（{@code false} 时只装配内核，不提供任何带归属校验的入口）。 */
    private boolean enabled = true;

    /** 每用户同时可跑的回合数上限，{@code <= 0} 表示不限。 */
    private int maxInflightTurnsPerUser = 2;

    /** 每用户可持有的会话数上限，{@code <= 0} 表示不限。 */
    private int maxSessionsPerUser = 20;

    /** 闲置会话回收开关。 */
    private boolean reaperEnabled = true;

    /**
     * 闲置多久算「可以回收了」。
     * <p>
     * <b>{@code ≤ 0}（或缺省）表示<b>不回收</b></b>，与同组的 {@link #maxInflightTurnsPerUser} /
     * {@link #maxSessionsPerUser} 同一个口径。这一条不能含糊：回收动作是「关闭会话并解绑归属」，
     * 客户端手里的 {@code sessionId} 下一次请求就变成 404；把越界值折算成一个很小的门槛
     * （例如 1 毫秒）等于「每轮扫描关掉所有空闲会话」，而配置的人以为自己在说「不限」。
     */
    private Duration idleTimeout = Duration.ofMinutes(30);

    /** 回收扫描间隔。 */
    private Duration sweepInterval = Duration.ofMinutes(5);

    /**
     * 命令域里被本层直接拒绝的命令名（不含前缀，别名各写一条）。
     * <p>
     * 缺省名单与理由见 {@link CommandNotAllowedException}：它们要么改写进程级状态，要么按参数操作
     * 任意会话（绕过归属校验），要么直接列出全进程会话。
     */
    private List<String> deniedCommands = new ArrayList<String>(
            Arrays.asList("new", "resume", "delete", "rm", "session", "sessions", "reload"));

    /**
     * 这些命令必须带会话才准执行：不带会话时它们写的是「下次新建会话的默认值」，那是进程级状态。
     */
    private List<String> sessionRequiredCommands = new ArrayList<String>(Arrays.asList("model", "agent", "a"));

    /**
     * 判断是否启用多用户门面。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用多用户门面。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取每用户并发回合上限。
     *
     * @return 上限；{@code <= 0} 表示不限
     */
    public int getMaxInflightTurnsPerUser() {
        return maxInflightTurnsPerUser;
    }

    /**
     * 设置每用户并发回合上限。
     *
     * @param maxInflightTurnsPerUser 上限；{@code <= 0} 表示不限
     */
    public void setMaxInflightTurnsPerUser(int maxInflightTurnsPerUser) {
        this.maxInflightTurnsPerUser = maxInflightTurnsPerUser;
    }

    /**
     * 获取每用户会话数上限。
     *
     * @return 上限；{@code <= 0} 表示不限
     */
    public int getMaxSessionsPerUser() {
        return maxSessionsPerUser;
    }

    /**
     * 设置每用户会话数上限。
     *
     * @param maxSessionsPerUser 上限；{@code <= 0} 表示不限
     */
    public void setMaxSessionsPerUser(int maxSessionsPerUser) {
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    /**
     * 判断是否启用闲置回收。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isReaperEnabled() {
        return reaperEnabled;
    }

    /**
     * 设置是否启用闲置回收。
     *
     * @param reaperEnabled 是否启用
     */
    public void setReaperEnabled(boolean reaperEnabled) {
        this.reaperEnabled = reaperEnabled;
    }

    /**
     * 获取闲置判定时长。
     *
     * @return 闲置时长；{@code ≤ 0}（或缺省）表示不回收
     */
    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    /**
     * 设置闲置判定时长。
     *
     * @param idleTimeout 闲置时长；{@code ≤ 0}（或缺省）表示不回收
     */
    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * 获取回收扫描间隔。
     *
     * @return 扫描间隔
     */
    public Duration getSweepInterval() {
        return sweepInterval;
    }

    /**
     * 设置回收扫描间隔。
     *
     * @param sweepInterval 扫描间隔
     */
    public void setSweepInterval(Duration sweepInterval) {
        this.sweepInterval = sweepInterval;
    }

    /**
     * 获取被拒绝的命令名清单。
     *
     * @return 命令名清单
     */
    public List<String> getDeniedCommands() {
        return deniedCommands;
    }

    /**
     * 设置被拒绝的命令名清单。
     *
     * @param deniedCommands 命令名清单，可为 {@code null}（等价于空清单）
     */
    public void setDeniedCommands(List<String> deniedCommands) {
        this.deniedCommands = deniedCommands == null ? new ArrayList<String>() : deniedCommands;
    }

    /**
     * 获取「必须带会话」的命令名清单。
     *
     * @return 命令名清单
     */
    public List<String> getSessionRequiredCommands() {
        return sessionRequiredCommands;
    }

    /**
     * 设置「必须带会话」的命令名清单。
     *
     * @param sessionRequiredCommands 命令名清单，可为 {@code null}（等价于空清单）
     */
    public void setSessionRequiredCommands(List<String> sessionRequiredCommands) {
        this.sessionRequiredCommands =
                sessionRequiredCommands == null ? new ArrayList<String>() : sessionRequiredCommands;
    }
}
