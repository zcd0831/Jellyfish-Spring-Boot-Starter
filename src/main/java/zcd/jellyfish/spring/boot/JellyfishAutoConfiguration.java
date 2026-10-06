package zcd.jellyfish.spring.boot;

import zcd.jellyfish.di.JellyfishRuntime;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * starter 的自动配置入口：条件装配 + 生命周期。
 * <p>
 * 整条装配链只有三步，每一步都有它必须在的理由：
 * <ol>
 *     <li>{@link JellyfishRuntimeConfiguration} 造出内核对象图（<b>只调一次装配</b>，不在 Spring 里重建）；</li>
 *     <li>{@link JellyfishFacadeConfiguration} 把那 21 个门面转发成 Bean，供业务代码按类型注入；</li>
 *     <li>本类注册 {@link JellyfishLifecycle}，把「内核什么时候起停」接到容器生命周期上。</li>
 * </ol>
 * <p>
 * <b>为什么关掉开关就整条不装</b>：条件在类级别，因此 {@code jellyfish.enabled=false} 时不但不启动内核，
 * 连对象图都不会构造——构造本身就有副作用（例如缓存保活器在构造期就排了扫描任务）。
 * 「不启用」应当是零成本，而不是「装好了但不用」。
 *
 * @author zcd
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "jellyfish", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JellyfishProperties.class)
@Import({JellyfishRuntimeConfiguration.class, JellyfishFacadeConfiguration.class})
public class JellyfishAutoConfiguration {

    /**
     * 注册内核生命周期宿主。
     *
     * @param jellyfishRuntime    内核运行时，不可为 {@code null}
     * @param jellyfishProperties starter 配置，不可为 {@code null}
     * @return 生命周期宿主，保证非 {@code null}
     */
    @Bean
    public JellyfishLifecycle jellyfishLifecycle(JellyfishRuntime jellyfishRuntime,
                                                 JellyfishProperties jellyfishProperties) {
        return new JellyfishLifecycle(jellyfishRuntime, jellyfishProperties);
    }
}
