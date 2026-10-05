package zcd.jellyfish.spring.boot;

import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.infra.permission.ApprovalChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * 内核的生命周期宿主：把「什么时候起、什么时候停」接到 Spring 的容器生命周期上。
 * <p>
 * <b>它只做三件事，顺序不能变</b>：
 * <ol>
 *     <li>写 {@link RuntimeInfo}——必须早于 {@code bootstrap()}。插件在 {@code start()} 里就会读它做降级决策
 *     （例如「没有审批通道就不要提供需要写权限的工具」），晚一步插件拿到的就是缺省值；</li>
 *     <li>挂载审批通道（仅在配置启用时）——同样早于 {@code bootstrap()}，这样 {@code RuntimeInfo} 里那句
 *     「具备审批通道」从第一刻起就是实话；</li>
 *     <li>{@code AgentHarness.bootstrap()}——它内部固定了十二步顺序（事件通道 → 指标 → 运行观测 → 系统命令 →
 *     子代理 → 配置 → 模型 / agent 索引 → 插件配置 → 插件启动 → 模型目录 → 会话恢复）。
 *     <b>本类不重排、也不拆开这些步骤</b>：那份顺序是内核的硬约束，只能有一处说法。</li>
 * </ol>
 * <p>
 * <b>phase 的取值是两个方向上的同一件事</b>：{@link #getPhase()} 小于 Web 容器的 phase
 * （Boot 的 {@code WebServerStartStopLifecycle} 用 {@code Integer.MAX_VALUE - 1}）。
 * Spring 启动按 phase 升序、停止按降序，因此这一条同时给出「启动时内核先于 Web 就绪」与
 * 「停止时 Web 先停止收请求、内核后关」。反过来配会让请求打进一个已经关掉的线程池。
 * <p>
 * <b>刻意不注册 JVM shutdown hook</b>：Spring 关上下文时就会走 {@link #stop()}；再叠一个 hook 只会让
 * 「谁先关」取决于 JVM 并不保证的 hook 执行顺序。
 *
 * @author zcd
 */
public class JellyfishLifecycle implements SmartLifecycle {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(JellyfishLifecycle.class);

    /**
     * 生命周期阶段：取最小值，即「启动最先、停止最后」。
     * <p>
     * <b>为什么是极值而不是「比 Web 容器小一点」</b>：需要保证的只有一条——内核早于 Web 容器就绪、
     * 晚于它停止（否则请求会打进一个已经关掉的线程池）。Spring 启动按 phase 升序、停止按降序，
     * 因此「比 Web 容器小」就等价于这条保证。但 Web 容器的 phase 是 Boot 的内部实现
     * （2.x 是 {@code Integer.MAX_VALUE - 1}，较新的版本已改成 {@code Integer.MAX_VALUE - 1024}），
     * 跟着它减一个魔数会在某次升级 Boot 时静默失效——而失效的表现是「偶发地打不通」，
     * 排查成本极高。取最小值就与那个数字无关，Boot 怎么改都不影响。
     * <p>
     * 副作用是内核会在其它 {@code SmartLifecycle} 之前启动。这是可接受的：{@code start()} 发生在所有单例
     * 都已实例化之后，而「先就绪、后被用到」正是依赖它的 Bean 想要的顺序。
     * <p>
     * <b>它是 public 的</b>：本 starter 里还有别的生命周期组件需要「排在内核之后、但在它关闭之前」
     * （例如闲置会话回收用 {@code PHASE + 1}），靠同一个常量推导比各自再写一个魔数可靠。
     */
    public static final int PHASE = Integer.MIN_VALUE;

    /** 装配好的内核对象图。 */
    private final JellyfishRuntime runtime;

    /** 配置。 */
    private final JellyfishProperties properties;

    /** 是否已启动：用于 {@link #isRunning()}、{@link #stop()} 的幂等与「没起过就别关」。 */
    private volatile boolean running;

    /**
     * 构造生命周期宿主。
     *
     * @param runtime    装配好的内核对象图，不可为 {@code null}
     * @param properties 配置，不可为 {@code null}
     */
    public JellyfishLifecycle(JellyfishRuntime runtime, JellyfishProperties properties) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        if (!properties.getLifecycle().isBootstrapOnStart()) {
            LOG.info("jellyfish.lifecycle.bootstrap-on-start=false，内核保持未启动；"
                    + "需要时自行调用 AgentHarness.bootstrap()");
            return;
        }
        runtime.runtimeInfoHolder().set(runtimeInfo());
        if (properties.getApproval().isEnabled()) {
            runtime.approvalChannel().attach();
            LOG.info("已挂载人工审批通道：askTools 里的工具将等待业务方裁决（缺省 120 秒超时后按拒绝处理）");
        }
        try {
            runtime.agentHarness().bootstrap();
        } catch (RuntimeException e) {
            // 启动失败必须让上下文也起不来：一个「内核半死、接口照常对外服务」的进程，
            // 表现是每个请求都失败，而根因早在启动日志里。这里先尽力收敛已经起来的资源
            // （事件通道、指标订阅、插件运行时可能已经起了），再原样抛出。
            LOG.error("内核启动失败，开始收敛已启动的资源", e);
            shutdownQuietly();
            throw e;
        }
        running = true;
        LOG.info("Jellyfish 内核已启动：processName={}，插件扫描目录={}，审批通道={}",
                properties.getProcessName(), properties.getPlugins().getRoots(),
                properties.getApproval().isEnabled() ? "已挂载" : "未挂载（需要审批的调用一律拒绝）");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        // 先摘审批、再关内核：让卡在审批等待上的回合以「通道已关闭」收敛，而不是被 shutdown 打断。
        // 两种都能收敛，区别只在日志里读不读得出「这是正常关停」。
        detachApprovalQuietly();
        try {
            runtime.agentHarness().shutdown();
        } finally {
            running = false;
        }
        LOG.info("Jellyfish 内核已关闭");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 构造交给插件的运行时信息快照。
     * <p>
     * <b>刻意不用 {@code RuntimeInfo.server(...)}</b>：那个工厂恒把 {@code supportsApproval} 置为 {@code true}，
     * 而本 starter 默认并不挂审批通道。声明一个做不到的能力比不声明更糟——插件会按「审批可用」去规划行为
     * （例如注册需要写权限的工具），结果是每次调用白等 120 秒再失败。因此这里如实填写，
     * 只在真的挂了通道时才说「具备」。
     *
     * @return 运行时信息快照，保证非 {@code null}
     */
    private RuntimeInfo runtimeInfo() {
        return new RuntimeInfo(RuntimeInfo.Shell.SERVER, false, properties.getApproval().isEnabled(),
                properties.getRuntime().isInteractive());
    }

    /**
     * 关闭路径上尽力收敛内核，绝不把关闭本身弄失败。
     */
    private void shutdownQuietly() {
        try {
            runtime.agentHarness().shutdown();
        } catch (RuntimeException e) {
            LOG.warn("启动失败后的内核收敛未能完成，继续抛出启动异常", e);
        }
    }

    /**
     * 摘下审批通道并排空未决请求，失败只记日志。
     */
    private void detachApprovalQuietly() {
        if (!properties.getApproval().isEnabled()) {
            return;
        }
        ApprovalChannel approvals = runtime.approvalChannel();
        try {
            approvals.detach();
        } catch (RuntimeException e) {
            LOG.warn("摘下审批通道失败，继续关闭内核", e);
        }
    }
}
