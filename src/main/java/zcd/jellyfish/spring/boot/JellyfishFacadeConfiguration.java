package zcd.jellyfish.spring.boot;

import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.session.SessionDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把内核里<b>「不按会话操作、也读不到别人数据」</b>的那几个门面暴露成 Spring Bean，供业务代码按类型注入。
 * <p>
 * <b>为什么不把内核门面都导出来</b>：{@code JellyfishRuntime} 上挂着一整套按 id 直接操作会话的能力
 * ——会话域（列全进程会话、删任意会话）、审批与提问通道（只认「某会话的头槽位」，不认调用方是谁）、
 * 外壳通道（按 id 订阅任意会话的事件流，等于读别人的对话）、命令域（改进程级「当前会话」指针）、
 * 扩展注册表（绕过插件生命周期直接注册工具）。这些一旦是 Bean，容器里<b>任何</b> Bean 都能注入，
 * 那本 starter 那套「先校验归属」的护栏（{@code user} 包）就只剩约定。因此它们<b>不进容器</b>：
 * 内核门面由 {@link JellyfishKernel} 持有（访问器包私有），业务侧要操作会话请走
 * {@code JellyfishUserFacade}（带归属校验），要扩展内核请写插件。
 * <p>
 * 留下来的判据是「<b>拿到它做不了跨用户的事</b>」：观测（{@code HealthCheck} / {@code RuntimeInfoHolder}）、
 * 配置读取（{@code RuntimeConfig} —— 注意它有 {@code refresh()}，那是维护动作，不该出现在请求路径上）、
 * 清单读取（{@code ModelManager} / {@code AgentManager}）、新建会话的待生效默认值（{@code SessionDefaults}，
 * 只影响之后新建的会话）、直接发起模型调用（{@code LlmClientFactory}，花的是调用方自己的额度）。
 * <p>
 * <b>这些 Bean 刻意不做 {@code @ConditionalOnMissingBean}</b>：它们是同一张对象图上的视图，
 * 允许「再注册一个」等于同一个 JVM 里出现第二套内核状态——而坏掉的方式是静默的
 * （配置刷了一份、另一份永远是空的）。要扩充能力请用内核的扩展点（插件），不要在容器里替换内核组件。
 *
 * @author zcd
 */
@Configuration(proxyBeanMethods = false)
public class JellyfishFacadeConfiguration {

    /**
     * 提供 LLM 客户端工厂。
     * <p>
     * 只在需要直接发起模型调用时用（内核的回合走不到它）。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return LLM 客户端工厂
     */
    @Bean
    public LlmClientFactory llmClientFactory(JellyfishKernel kernel) {
        return kernel.runtime().llmClientFactory();
    }

    /**
     * 提供模型门面。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return 模型门面
     */
    @Bean
    public ModelManager modelManager(JellyfishKernel kernel) {
        return kernel.runtime().modelManager();
    }

    /**
     * 提供运行时配置门面。
     * <p>
     * <b>它的 {@code refresh()} 会重新加载配置</b>（包括 agent 的权限策略）：那是维护动作，
     * 请挂在运维入口上，别让普通请求路径碰它。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return 运行时配置门面
     */
    @Bean
    public RuntimeConfig runtimeConfig(JellyfishKernel kernel) {
        return kernel.runtime().runtimeConfig();
    }

    /**
     * 提供 agent 门面。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return agent 门面
     */
    @Bean
    public AgentManager agentManager(JellyfishKernel kernel) {
        return kernel.runtime().agentManager();
    }

    /**
     * 提供新建会话的待生效默认值。
     * <p>
     * 它是<b>进程级</b>的：改它会影响之后所有新建的会话（含别人的），因此只该由启动配置或管理入口设置。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return 会话默认值
     */
    @Bean
    public SessionDefaults sessionDefaults(JellyfishKernel kernel) {
        return kernel.runtime().sessionDefaults();
    }

    /**
     * 提供运行时信息持有者。
     * <p>
     * 写入时机在 {@link JellyfishLifecycle#start()}（必须早于插件启动），接入方只读不写。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return 运行时信息持有者
     */
    @Bean
    public RuntimeInfoHolder runtimeInfoHolder(JellyfishKernel kernel) {
        return kernel.runtime().runtimeInfoHolder();
    }

    /**
     * 提供健康检查汇总。
     * <p>
     * 检查项是「模型 → 插件 → 事件通道 → 压缩」四项，可挂到接入方自己的健康端点或 Actuator 上。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @return 健康检查汇总
     */
    @Bean
    public HealthCheck healthCheck(JellyfishKernel kernel) {
        return kernel.runtime().healthCheck();
    }
}
