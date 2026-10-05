package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import zcd.jellyfish.infra.session.SessionManager;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code META-INF/spring.factories} 的注册测试：证明这个 starter 被 Spring Boot **真的发现了**。
 * <p>
 * <b>为什么需要单独一个测试</b>：其它用例都用 {@code ApplicationContextRunner} +
 * {@code AutoConfigurations.of(JellyfishAutoConfiguration.class)}，那是**显式点名**加载配置类——
 * 于是 {@code spring.factories} 里把类名写错、漏写、或换了 Boot 版本后注册方式失效，这些用例全都照样绿。
 * 而现场表现是「starter 引进来了，`@Autowired SessionManager` 却没人提供」——正是最难猜的一类问题。
 * <p>
 * 因此这里走完整的 {@link SpringApplication}：它会去读 {@code spring.factories}，走的就是接入方真实经历的那条路。
 * 用 {@code WebApplicationType.NONE} 起一个非 Web 容器，避免测试依赖 Web 栈。
 *
 * @author zcd
 */
@DisplayName("spring.factories 自动配置注册")
class JellyfishStarterRegistrationTest {

    /** 插件扫描目录，指向临时目录以免扫到使用者的真实插件。 */
    @TempDir
    Path pluginsRoot;

    @Test
    @DisplayName("SpringApplication 能通过 spring.factories 自动装配出内核 Bean")
    void spring_application_should_register_starter_via_spring_factories() {
        // Given：接入方最朴素的用法——一个空的 @Configuration，加一份 application.yml
        SpringApplication application = new SpringApplication(StubConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(defaultProperties());

        // When
        try (ConfigurableApplicationContext context = application.run()) {

            // Then：Bean 在，说明 spring.factories 的注册生效了
            // 用 getBeanNamesForType 而不是 hasSingleBean：后者是 ApplicationContextRunner 才给的断言，
            // 这里的上下文是裸的 ConfigurableApplicationContext
            assertEquals(1, context.getBeanNamesForType(SessionManager.class).length);
            assertEquals(1, context.getBeanNamesForType(JellyfishLifecycle.class).length);
            assertNotNull(context.getBean(SessionManager.class));
        }
    }

    /**
     * 构造一份「不影响使用者真实环境」的配置：清空四份配置路径、插件目录指向临时目录、不启动内核。
     * <p>
     * 不启动内核是刻意的：本用例要验的是「自动配置有没有被发现」，起一遍内核只会更慢；
     * 真启动的那条路径由 {@code JellyfishAutoConfigurationTest} 覆盖。
     *
     * @return 属性表
     */
    private Map<String, Object> defaultProperties() {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("jellyfish.models.global-path", "");
        properties.put("jellyfish.models.project-path", "");
        properties.put("jellyfish.agents.global-path", "");
        properties.put("jellyfish.agents.project-path", "");
        properties.put("jellyfish.settings.global-path", "");
        properties.put("jellyfish.settings.project-path", "");
        properties.put("jellyfish.plugins.roots", pluginsRoot.toString());
        properties.put("jellyfish.lifecycle.bootstrap-on-start", "false");
        return properties;
    }

    /**
     * 接入方的最小配置类：只开自动配置，自己什么也不声明。
     *
     * @author zcd
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class StubConfig {
    }
}
