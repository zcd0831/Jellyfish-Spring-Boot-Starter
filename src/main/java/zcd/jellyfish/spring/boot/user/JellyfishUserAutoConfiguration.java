package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.spring.boot.JellyfishAutoConfiguration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 多用户护栏的自动配置。
 * <p>
 * <b>它是一层独立的东西，不是内核的一部分</b>：内核不认识「用户」，它的会话表、命令域、审批通道都按
 * 「一个人在终端上操作」设计。这里把「谁是谁的会话」「一个人最多能用多少」「什么命令不能放行」
 * 收成一个门面（{@link JellyfishUserFacade}），业务侧只认它。
 * <p>
 * <b>为什么用 {@code @AutoConfigureAfter} 而不是 {@code @Import}</b>：本配置需要内核的
 * {@link JellyfishRuntime} 已经装配好（{@code @ConditionalOnBean} 才有意义），而自动配置之间的顺序
 * 由「谁在 spring.factories 里排前面」是<b>不保证</b>的——{@code @AutoConfigureAfter} 是唯一能把它
 * 变成确定事实的声明。少了它，{@code @ConditionalOnBean} 会在内核还没装配时就求值，
 * 于是「护栏时有时无」——取决于扫描顺序这种没人能预料的事。
 *
 * @author zcd
 */
@AutoConfiguration
@AutoConfigureAfter(JellyfishAutoConfiguration.class)
@ConditionalOnProperty(prefix = "jellyfish.user", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(JellyfishRuntime.class)
@EnableConfigurationProperties(JellyfishUserProperties.class)
public class JellyfishUserAutoConfiguration {

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
     * 提供审批网关。
     *
     * @param runtime 内核运行时，不可为 {@code null}
     * @param owners  会话归属存储，不可为 {@code null}
     * @return 审批网关，保证非 {@code null}
     */
    @Bean
    public ApprovalGateway jellyfishApprovalGateway(JellyfishRuntime runtime, SessionOwnerStore owners) {
        return new ApprovalGateway(runtime.approvalChannel(), owners);
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
     * 提供多用户门面：业务侧与内核之间的唯一入口。
     *
     * @param runtime  内核运行时，不可为 {@code null}
     * @param owners   会话归属存储，不可为 {@code null}
     * @param quota    配额闸门，不可为 {@code null}
     * @param approvals 审批网关，不可为 {@code null}
     * @param gate     命令闸门，不可为 {@code null}
     * @return 多用户门面，保证非 {@code null}
     */
    @Bean
    public JellyfishUserFacade jellyfishUserFacade(JellyfishRuntime runtime, SessionOwnerStore owners,
                                                  UserQuotaGuard quota, ApprovalGateway approvals,
                                                  CommandGate gate) {
        return new JellyfishUserFacade(runtime.sessionManager(), runtime.conversationService(),
                runtime.shellStreams(), runtime.turnRegistry(), runtime.commandManager(), owners, quota,
                approvals, gate);
    }

    /**
     * 提供闲置会话回收器。
     * <p>
     * 它是一个 {@code SmartLifecycle}，phase 比内核大 1：内核启动之后才开始扫，内核关闭之前就停下。
     * 不启动内核（{@code bootstrap-on-start=false}）时它照样会被 Spring 起来，但那时会话表是空的，
     * 扫描是空转——接入方若要自己控制启动时机，可以把 {@code jellyfish.user.reaper-enabled} 一起关掉。
     *
     * @param runtime    内核运行时，不可为 {@code null}
     * @param owners     会话归属存储，不可为 {@code null}
     * @param properties 配置，不可为 {@code null}
     * @return 回收器，保证非 {@code null}
     */
    @Bean
    @ConditionalOnProperty(prefix = "jellyfish.user", name = "reaper-enabled", havingValue = "true",
            matchIfMissing = true)
    public IdleSessionReaper jellyfishIdleSessionReaper(JellyfishRuntime runtime, SessionOwnerStore owners,
                                                       JellyfishUserProperties properties) {
        return new IdleSessionReaper(runtime.sessionManager(), owners, runtime.turnRegistry(), properties);
    }
}
