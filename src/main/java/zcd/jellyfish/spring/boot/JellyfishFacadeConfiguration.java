package zcd.jellyfish.spring.boot;

import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link JellyfishRuntime} 的 21 个门面各暴露成一个 Spring Bean，供业务代码按类型注入。
 * <p>
 * <b>为什么需要这一层转发</b>：内核的门面只能从 {@code JellyfishRuntime} 上取，注入点写成
 * {@code runtime.agentHarness()} 会把「怎么拿到内核」这件事渗进每一个业务类。转发一次之后，
 * 业务代码只依赖它真正要用的那个类型（例如 {@code SessionManager}），注入什么一目了然。
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
     * @param runtime 内核运行时，不可为 {@code null}
     * @return LLM 客户端工厂
     */
    @Bean
    public LlmClientFactory llmClientFactory(JellyfishRuntime runtime) {
        return runtime.llmClientFactory();
    }

    /**
     * 提供模型门面。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 模型门面
     */
    @Bean
    public ModelManager modelManager(JellyfishRuntime runtime) {
        return runtime.modelManager();
    }

    /**
     * 提供运行时配置门面。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 运行时配置门面
     */
    @Bean
    public RuntimeConfig runtimeConfig(JellyfishRuntime runtime) {
        return runtime.runtimeConfig();
    }

    /**
     * 提供 agent 运行时宿主。
     * <p>
     * <b>正常用法不需要注入它</b>：起停由 {@link JellyfishLifecycle} 负责，
     * 而 {@code chat(...)} 是内核的内部接缝（外壳走 {@code ConversationService.submit}）。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return agent 运行时宿主
     */
    @Bean
    public AgentHarness agentHarness(JellyfishRuntime runtime) {
        return runtime.agentHarness();
    }

    /**
     * 提供 agent 门面。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return agent 门面
     */
    @Bean
    public AgentManager agentManager(JellyfishRuntime runtime) {
        return runtime.agentManager();
    }

    /**
     * 提供权限判定器。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 权限判定器
     */
    @Bean
    public PermissionManager permissionManager(JellyfishRuntime runtime) {
        return runtime.permissionManager();
    }

    /**
     * 提供人工审批通道。
     * <p>
     * 接入方要用它做三件事：读本会话待裁决的那一条（{@code pending(sessionId)}）、
     * 读整条队列（{@code pendingApprovals(sessionId)}）、裁决（{@code resolve(id, approved)}）。
     * <b>只有头槽位那一条能被裁决</b>，排队中的请求调用 {@code resolve} 等于无事发生。
     * <p>
     * <b>多用户场景不要直接用本 Bean</b>：{@code resolve} 只校验「这条请求是不是它自己那个会话的头槽位」，
     * 不校验调用方说的会话对不对。用 {@code user} 包下的审批网关（它先校验归属）。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 人工审批通道
     */
    @Bean
    public ApprovalChannel approvalChannel(JellyfishRuntime runtime) {
        return runtime.approvalChannel();
    }

    /**
     * 提供提问通道。
     * <p>
     * 接入方要用它做三件事：读本会话待作答的那一条（{@code pending(sessionId)}）、
     * 读整条队列（{@code pendingAsks(sessionId)}）、作答（{@code resolve(id, answer)}）。
     * 与审批同口径：<b>只有头槽位那一条能被作答</b>，排队中的提问调用 {@code resolve} 等于无事发生。
     * <p>
     * 作答用内核 api 侧的 {@code AskAnswer}：{@code answered(optionId)} 表示选了某个候选项，
     * {@code custom(text)} 表示用户自己填的答案，{@code cancelled(reason)} 表示放弃作答。
     * 三个都是正常终态——提问不是审批，放弃与无人作答都不代表任何调用被拒绝。
     * <p>
     * <b>多用户场景不要直接用本 Bean</b>：{@code resolve} 不校验调用方说的会话是不是自己的。
     * 用 {@code user} 包下的提问网关（它先校验归属）。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 提问通道
     */
    @Bean
    public AskChannel askChannel(JellyfishRuntime runtime) {
        return runtime.askChannel();
    }

    /**
     * 提供会话压缩器。
     * <p>
     * 压缩需要策略插件（{@code jellyfish-plugin-compact} 或同类）：没有它时压缩整体不可用，不回退内置。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 会话压缩器
     */
    @Bean
    public ConversationCompactor conversationCompactor(JellyfishRuntime runtime) {
        return runtime.conversationCompactor();
    }

    /**
     * 提供输入指令服务。
     * <p>
     * 它服务的是 {@code !命令} / {@code @路径} 这类行首标记，属于交互式用法；
     * 在按 sessionId 寻址的服务端应用里通常用不到。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 输入指令服务
     */
    @Bean
    public InputDirectives inputDirectives(JellyfishRuntime runtime) {
        return runtime.inputDirectives();
    }

    /**
     * 提供命令域服务。
     * <p>
     * <b>多用户场景要小心</b>：{@code /new} 与 {@code /resume} 会改写进程级的「当前会话」指针。
     * 内核的服务化外壳自己就不读那个指针，也不暴露这两条命令（按 sessionId 寻址）。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 命令域服务
     */
    @Bean
    public CommandManager commandManager(JellyfishRuntime runtime) {
        return runtime.commandManager();
    }

    /**
     * 提供会话域服务。
     * <p>
     * <b>两条使用纪律</b>：① <b>永不调用 {@code switchTo} / {@code createDefault}</b>——
     * 那是进程级单指针，多用户下会让一个用户的会话变成别人的「当前会话」；
     * 建会话请用 {@code create(agentId, provider, model)}，并自己记下归属。
     * ② <b>不要把 {@code all()} 直接暴露给业务侧</b>——它返回本进程全部会话，内核没有「用户」这一维。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 会话域服务
     */
    @Bean
    public SessionManager sessionManager(JellyfishRuntime runtime) {
        return runtime.sessionManager();
    }

    /**
     * 提供新建会话的待生效默认值。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 会话默认值
     */
    @Bean
    public SessionDefaults sessionDefaults(JellyfishRuntime runtime) {
        return runtime.sessionDefaults();
    }

    /**
     * 提供同步扩展点策略。
     * <p>
     * 接入方一般无需直接用；插件能力面走的是内核的 {@code PluginContext}。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 同步扩展点策略
     */
    @Bean
    public ExtensionRegistry extensionRegistry(JellyfishRuntime runtime) {
        return runtime.extensionRegistry();
    }

    /**
     * 提供事件通道。
     * <p>
     * <b>它是可丢的异步通道</b>（队列有界、满了丢弃并记账），因此适合观测类通知，
     * 不适合当作业务事实的来源。订阅务必要能退订。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 事件通道
     */
    @Bean
    public EventChannel eventChannel(JellyfishRuntime runtime) {
        return runtime.eventChannel();
    }

    /**
     * 提供运行时信息持有者。
     * <p>
     * 写入时机在 {@link JellyfishLifecycle#start()}（必须早于插件启动），接入方只读不写。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 运行时信息持有者
     */
    @Bean
    public RuntimeInfoHolder runtimeInfoHolder(JellyfishRuntime runtime) {
        return runtime.runtimeInfoHolder();
    }

    /**
     * 提供会话提交服务：<b>业务侧发起一次对话的唯一入口</b>。
     * <p>
     * 它一处保证了分流顺序（命令判定 → 输入改写 → 输入指令 → 起回合），因此外壳不必各自重写。
     * 同一会话已有在途回合时抛 {@code TurnInProgressException}（对应 HTTP 409 的语义），
     * 请把它映射成业务自己的错误码而不是 500。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 会话提交服务
     */
    @Bean
    public ConversationService conversationService(JellyfishRuntime runtime) {
        return runtime.conversationService();
    }

    /**
     * 提供在途回合表。
     * <p>
     * 调用点是「取消在途回合」这一个场景。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 在途回合表
     */
    @Bean
    public TurnRegistry turnRegistry(JellyfishRuntime runtime) {
        return runtime.turnRegistry();
    }

    /**
     * 提供外壳通道门面：<b>订阅回合事件的唯一入口</b>。
     * <p>
     * 按会话订阅（{@code subscribe(sessionId, listener)}），订阅者必须快且线程安全（回调在回合线程上同步执行），
     * 且必须成对退订——断开连接时忘记退订会一直持有订阅者。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 外壳通道门面
     */
    @Bean
    public ShellStreams shellStreams(JellyfishRuntime runtime) {
        return runtime.shellStreams();
    }

    /**
     * 提供 agent run 事件总线（子代理的运行起止）。
     * <p>
     * <b>它是进程级总线，不按会话过滤</b>：订阅者必须自己按 {@code parentSessionId} 过滤，
     * 否则会把别的用户的子代理事件推给当前请求。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return run 事件总线
     */
    @Bean
    public RunEventBus runEventBus(JellyfishRuntime runtime) {
        return runtime.runEventBus();
    }

    /**
     * 提供健康检查汇总。
     * <p>
     * 检查项是「模型 → 插件 → 事件通道 → 压缩」四项，可挂到接入方自己的健康端点或 Actuator 上。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @return 健康检查汇总
     */
    @Bean
    public HealthCheck healthCheck(JellyfishRuntime runtime) {
        return runtime.healthCheck();
    }
}
