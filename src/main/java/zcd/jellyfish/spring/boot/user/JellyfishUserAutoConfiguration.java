package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.spring.boot.JellyfishAutoConfiguration;
import zcd.jellyfish.spring.boot.JellyfishKernel;
import zcd.jellyfish.spring.boot.JellyfishUserWiringConfiguration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * 多用户护栏层的自动配置入口。
 * <p>
 * <b>它是一层独立的东西，不是内核的一部分</b>：内核不认识「用户」，它的会话表、命令域、审批通道都按
 * 「一个人在终端上操作」设计。这里把「谁是谁的会话」「一个人最多能用多少」「什么命令不能放行」
 * 收成一个门面（{@link JellyfishUserFacade}），业务侧只认它。
 * <p>
 * <b>Bean 声明在 {@link JellyfishUserWiringConfiguration}，本类只做「什么时候装」</b>：装配那一半需要
 * 内核的<b>原始能力</b>（审批通道、会话域、提交服务……），而那些能力只能从包私有的访问器取到，
 * 因此装配代码必须与 {@link JellyfishKernel} 同包。理由写在那个类上，一句话：
 * <b>拿原始能力的地方必须与持有者同包，包可见性就是这道边界本身</b>。
 * <p>
 * <b>为什么用 {@code @AutoConfigureAfter} 而不是 {@code @Import}</b>：本配置的 {@code @ConditionalOnBean}
 * 需要内核的持有者 Bean 已经装配好，而自动配置之间的顺序由「谁在 spring.factories 里排前面」是
 * <b>不保证</b>的——{@code @AutoConfigureAfter} 是唯一能把它变成确定事实的声明。少了它，
 * {@code @ConditionalOnBean} 会在内核还没装配时就求值，于是「护栏时有时无」——取决于扫描顺序这种
 * 没人能预料的事。
 *
 * @author zcd
 */
@AutoConfiguration
@AutoConfigureAfter(JellyfishAutoConfiguration.class)
@ConditionalOnProperty(prefix = "jellyfish.user", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(JellyfishKernel.class)
@EnableConfigurationProperties(JellyfishUserProperties.class)
@Import(JellyfishUserWiringConfiguration.class)
public class JellyfishUserAutoConfiguration {
}
