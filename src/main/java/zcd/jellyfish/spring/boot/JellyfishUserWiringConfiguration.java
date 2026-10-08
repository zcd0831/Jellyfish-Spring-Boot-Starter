package zcd.jellyfish.spring.boot;

import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.spring.boot.user.ApprovalGateway;
import zcd.jellyfish.spring.boot.user.AskGateway;
import zcd.jellyfish.spring.boot.user.CommandGate;
import zcd.jellyfish.spring.boot.user.IdleSessionReaper;
import zcd.jellyfish.spring.boot.user.InMemorySessionOwnerStore;
import zcd.jellyfish.spring.boot.user.JellyfishUserFacade;
import zcd.jellyfish.spring.boot.user.JellyfishUserProperties;
import zcd.jellyfish.spring.boot.user.SessionOwnerStore;
import zcd.jellyfish.spring.boot.user.UserQuotaGuard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多用户护栏层的装配：网关、门面、闲置会话回收器。
 * <p>
 * <b>为什么这几个 Bean 的装配写在 {@code boot} 包而不是 {@code user} 包</b>：它们都要内核的
 * <b>原始能力</b>（审批通道、提问通道、会话域、提交服务、外壳通道、在途回合表、命令域），而那些能力
 * 只能从 {@link JellyfishKernel} 的<b>包私有</b>访问器取到（理由见 {@link JellyfishKernel}）。
 * 包可见性就是这道边界本身：装配代码必须与持有者同包，于是「谁能拿到内核」这件事在编译期就定了。
 * <p>
 * <b>Bean 与它「什么时候装」的条件分在两个类里</b>：本类只声明 Bean（{@code user.JellyfishUserAutoConfiguration}
 * 负责条件与顺序），因为本类必须用 {@code @Import} 引进来才拿得到包私有访问器，而
 * {@code @ConditionalOnBean} 在 {@code @Import} 的类上是按解析顺序求值的——那是个会静默少装一层护栏的坑。
 * <p>
 * <b>SPI 也声明在这里</b>（归属存储、配额闸门、命令闸门）：这一层要么在、要么全不在。
 * 把它们拆去另一个配置类会让「只排除其中一个」变成启动期缺 Bean——失败方式是响的，但没必要制造。
 * <p>
 * <b>本类产出的 Bean 都是「已经带上归属校验」的对象</b>：{@link ApprovalGateway} / {@link AskGateway}
 * 每条路径先校验会话归属，{@link JellyfishUserFacade} 是业务侧与内核之间的唯一入口。
 * 也就是说，<b>这里是把原始能力收成安全对象的地方</b>，收好之后才交给容器。
 *
 * @author zcd
 */
@Configuration(proxyBeanMethods = false)
public class JellyfishUserWiringConfiguration {

    /**
     * 提供会话归属存储。
     * <p>
     * <b>本 Bean 是唯一允许被接入方覆盖的</b>（{@code @ConditionalOnMissingBean}），因为它是一条 SPI，
     * 与内核组件不同：内核那些门面是同一张对象图上的视图，替换它们等于在同一个 JVM 里造出第二套内核状态；
     * 而归属存储天然属于业务——存在内存里还是关系库里，只有接入方知道。
     * <b>会话一旦持久化，归属也必须一起持久化</b>，否则重启后会话还在、归属没了，那些会话就成了
     * 谁也访问不到的孤儿。
     *
     * @return 会话归属存储，保证非 {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(SessionOwnerStore.class)
    public SessionOwnerStore sessionOwnerStore() {
        return new InMemorySessionOwnerStore();
    }

    /**
     * 提供每用户配额闸门。
     *
     * @param properties 配置，不可为 {@code null}
     * @return 配额闸门，保证非 {@code null}
     */
    @Bean
    public UserQuotaGuard jellyfishUserQuotaGuard(JellyfishUserProperties properties) {
        return new UserQuotaGuard(properties);
    }

    /**
     * 提供命令闸门。
     *
     * @param properties 配置，不可为 {@code null}
     * @return 命令闸门，保证非 {@code null}
     */
    @Bean
    public CommandGate jellyfishCommandGate(JellyfishUserProperties properties) {
        return new CommandGate(properties);
    }

    /**
     * 提供审批网关。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @param owners 会话归属存储，不可为 {@code null}
     * @return 审批网关，保证非 {@code null}
     */
    @Bean
    public ApprovalGateway jellyfishApprovalGateway(JellyfishKernel kernel, SessionOwnerStore owners) {
        return new ApprovalGateway(kernel.runtime().approvalChannel(), owners);
    }

    /**
     * 提供提问网关。
     * <p>
     * <b>它与审批网关一样是必需的，不是可选的加固</b>：内核的 {@code AskChannel.resolve(id, answer)}
     * 同样只校验「是不是它自己会话的头槽位」，不校验调用方说的会话对不对。少了这一层，
     * 任何人都能替别人作答；而提问的答案会作为工具结果原文进那个会话的模型上下文，
     * 因此它同时还是一条内容注入路径。
     *
     * @param kernel 内核持有者，不可为 {@code null}
     * @param owners 会话归属存储，不可为 {@code null}
     * @return 提问网关，保证非 {@code null}
     */
    @Bean
    public AskGateway jellyfishAskGateway(JellyfishKernel kernel, SessionOwnerStore owners) {
        return new AskGateway(kernel.runtime().askChannel(), owners);
    }

    /**
     * 提供多用户门面：业务侧与内核之间的唯一入口。
     *
     * @param kernel    内核持有者，不可为 {@code null}
     * @param owners    会话归属存储，不可为 {@code null}
     * @param quota     配额闸门，不可为 {@code null}
     * @param approvals 审批网关，不可为 {@code null}
     * @param asks      提问网关，不可为 {@code null}
     * @param gate      命令闸门，不可为 {@code null}
     * @return 多用户门面，保证非 {@code null}
     */
    @Bean
    public JellyfishUserFacade jellyfishUserFacade(JellyfishKernel kernel, SessionOwnerStore owners,
                                                  UserQuotaGuard quota, ApprovalGateway approvals,
                                                  AskGateway asks, CommandGate gate) {
        JellyfishRuntime runtime = kernel.runtime();
        return new JellyfishUserFacade(runtime.sessionManager(), runtime.conversationService(),
                runtime.shellStreams(), runtime.turnRegistry(), runtime.commandManager(), owners, quota,
                approvals, asks, gate);
    }

    /**
     * 提供闲置会话回收器。
     * <p>
     * 它是一个 {@code SmartLifecycle}，phase 比内核大 1：内核启动之后才开始扫，内核关闭之前就停下。
     * 不启动内核（{@code bootstrap-on-start=false}）时它照样会被 Spring 起来，但那时会话表是空的，
     * 扫描是空转——接入方若要自己控制启动时机，可以把 {@code jellyfish.user.reaper-enabled} 一起关掉。
     *
     * @param kernel     内核持有者，不可为 {@code null}
     * @param owners     会话归属存储，不可为 {@code null}
     * @param properties 配置，不可为 {@code null}
     * @return 回收器，保证非 {@code null}
     */
    @Bean
    @ConditionalOnProperty(prefix = "jellyfish.user", name = "reaper-enabled", havingValue = "true",
            matchIfMissing = true)
    public IdleSessionReaper jellyfishIdleSessionReaper(JellyfishKernel kernel, SessionOwnerStore owners,
                                                       JellyfishUserProperties properties) {
        return new IdleSessionReaper(kernel.runtime().sessionManager(), owners,
                kernel.runtime().turnRegistry(), properties);
    }
}
