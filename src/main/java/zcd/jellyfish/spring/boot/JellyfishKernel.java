package zcd.jellyfish.spring.boot;

import zcd.jellyfish.di.JellyfishAssembler;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.infra.config.AppConfig;

import java.util.Objects;

/**
 * 内核对象图的持有者：<b>把 {@link JellyfishRuntime} 留在 starter 内部，不交给 Spring 容器</b>。
 * <p>
 * <b>为什么需要它</b>：{@code JellyfishRuntime} 上挂着整张内核能力面——会话域（按 id 操作任意会话）、
 * 审批与提问通道（只认「某会话的头槽位」，不认调用方是谁）、外壳通道（按 id 订阅任意会话的事件流）。
 * 它一旦成为 Bean，<b>同一个容器里的任何 Bean 都能把它注入进来</b>，于是本 starter 那一整套
 * 「先校验归属」的护栏（{@code user} 包）就降级成了一句约定：绕过去只要写一行 {@code runtime.approvalChannel()}。
 * <p>
 * <b>边界靠 Java 的包可见性立起来</b>：{@link #runtime()} 是<b>包私有</b>的，因此只有本包（starter 自己的装配
 * 代码）能拿到内核门面；接入方的 Bean 在别的包里，即使注入了本 Bean 也调不到那个访问器——这不是约定，
 * 是编译期就拿不到。
 * <p>
 * <b>对外是有意「无公开能力」的</b>：本类不提供任何公开方法。接入方要内核能力有三条正路——
 * 用 {@code JellyfishUserFacade}（带归属校验）、写插件（内核的扩展点）、或者自己
 * {@code JellyfishAssembler.create(...)} 造一套自己的（那时你同时接管了它的生命周期）。
 *
 * @author zcd
 */
public final class JellyfishKernel {

    /** 内核运行时门面：只在本包内传递。 */
    private final JellyfishRuntime runtime;

    /**
     * 构造持有者。
     *
     * @param runtime 内核运行时门面，不可为 {@code null}
     */
    JellyfishKernel(JellyfishRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    /**
     * 装配一套内核并持有它。
     * <p>
     * 装配只做一次：整张对象图（63 个节点）由 {@link JellyfishAssembler} 一次建成，
     * 本类只是它的落点。
     *
     * @param appConfig 应用级配置，不可为 {@code null}
     * @return 持有者，保证非 {@code null}
     */
    static JellyfishKernel assemble(AppConfig appConfig) {
        return new JellyfishKernel(JellyfishAssembler.create(appConfig));
    }

    /**
     * 取内核运行时门面。
     * <p>
     * <b>包私有是这套边界的全部实现</b>：公开它就等于把上面那段话作废。要在别的地方用它，
     * 先想清楚「那个地方凭什么能操作别人的会话」。
     *
     * @return 内核运行时门面，保证非 {@code null}
     */
    JellyfishRuntime runtime() {
        return runtime;
    }
}
