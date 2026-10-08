package zcd.jellyfish.spring.boot;

import zcd.jellyfish.infra.config.AppConfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配根：把内核的对象图造出来，交给 starter 自己持有，<b>不交给 Spring 容器</b>。
 * <p>
 * <b>为什么只暴露 {@link JellyfishKernel} 这一个入口</b>：内核门面（{@code JellyfishRuntime}）上挂着
 * 「按 id 操作任意会话」「裁决任意会话的审批」这类能力，而 Spring 容器里的 Bean 之间没有权限边界——
 * 谁注入到谁就能用。因此它不进容器，只由 starter 自己的装配代码通过包私有的访问器取用；
 * 接入方默认拿到的只有带归属校验的 {@code JellyfishUserFacade}。理由与代价写在 {@link JellyfishKernel} 上。
 * <p>
 * <b>为什么只有两个 Bean</b>：整张图（63 个节点）由 {@code JellyfishAssembler} 一次装配完成，
 * 这里不逐个注册内核的 55 个带 {@code @Inject} 的类。装配知识只写一份（在 {@code jellyfish-di} 里），
 * 内核新增绑定时本模块不需要改——这是「复用内核装配」而不是「在 Spring 里再装配一遍」的关键。
 * <p>
 * <b>也不用 Dagger 生成的组件</b>：那会把 Dagger 的运行期依赖带进接入方的技术栈，
 * 而 {@code JellyfishRuntime} 这个门面接口本来就是为「换一个容器也能用」准备的。
 *
 * @author zcd
 */
@Configuration(proxyBeanMethods = false)
public class JellyfishRuntimeConfiguration {

    /**
     * 把 yml 里的路径与开关转成内核的部署事实载体。
     * <p>
     * <b>刻意不走 {@code ConfigLoader.loadAppConfig()}</b>：它读的是写死的 {@code classpath:config.json}，
     * 而 classpath 上的同名资源谁生效取决于顺序。本 starter 若自带一份就会与接入方项目里的同名文件
     * 争夺优先级，因此这里直接构造，让 {@code application.yml} 成为唯一入口。
     *
     * @param properties starter 配置，不可为 {@code null}
     * @return 应用级配置，保证非 {@code null}
     */
    @Bean
    public AppConfig jellyfishAppConfig(JellyfishProperties properties) {
        return properties.toAppConfig();
    }

    /**
     * 装配内核对象图并持有它。
     * <p>
     * {@code destroyMethod = ""} 是刻意的：收敛由 {@link JellyfishLifecycle} 按 phase 编排，
     * 不由 Bean 销毁触发。若留给 Spring 自动推断（它会去找 {@code close()} / {@code shutdown()}），
     * 就会多出一条与生命周期并行的关闭路径——两条路径的顺序没有保证。
     *
     * @param jellyfishAppConfig 应用级配置，不可为 {@code null}
     * @return 内核持有者，保证非 {@code null}
     */
    @Bean(destroyMethod = "")
    public JellyfishKernel jellyfishKernel(AppConfig jellyfishAppConfig) {
        return JellyfishKernel.assemble(jellyfishAppConfig);
    }
}
