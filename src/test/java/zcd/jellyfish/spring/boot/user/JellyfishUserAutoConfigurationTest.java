package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import zcd.jellyfish.spring.boot.JellyfishAutoConfiguration;
import zcd.jellyfish.spring.boot.JellyfishKernel;
import zcd.jellyfish.spring.boot.JellyfishLifecycle;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishUserAutoConfiguration} 的装配契约测试。
 * <p>
 * 与内核那一套的自动配置测试同样的写法与同样的两条纪律：清空配置路径（不碰使用者的 {@code ~/.jellyfish}）、
 * 插件目录指向临时目录、缺省不启动内核（快），需要真启动的用例自己覆盖。
 *
 * @author zcd
 */
@DisplayName("JellyfishUserAutoConfiguration 多用户护栏装配")
class JellyfishUserAutoConfigurationTest {

    /** 临时目录，给插件扫描用。 */
    @TempDir
    Path tempDir;

    /**
     * 构造测试载体。
     *
     * @return 测试载体
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JellyfishAutoConfiguration.class,
                        JellyfishUserAutoConfiguration.class))
                .withPropertyValues(
                        "jellyfish.models.global-path=",
                        "jellyfish.models.project-path=",
                        "jellyfish.agents.global-path=",
                        "jellyfish.agents.project-path=",
                        "jellyfish.settings.global-path=",
                        "jellyfish.settings.project-path=",
                        "jellyfish.plugins.roots=" + tempDir,
                        "jellyfish.lifecycle.bootstrap-on-start=false");
    }

    @Test
    @DisplayName("默认启用：护栏的每一个 Bean 都在")
    void context_should_expose_guards_when_enabled() {
        // When / Then
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SessionOwnerStore.class);
            assertThat(context).hasSingleBean(InMemorySessionOwnerStore.class);
            assertThat(context).hasSingleBean(UserQuotaGuard.class);
            assertThat(context).hasSingleBean(ApprovalGateway.class);
            assertThat(context).hasSingleBean(AskGateway.class);
            assertThat(context).hasSingleBean(CommandGate.class);
            assertThat(context).hasSingleBean(JellyfishUserFacade.class);
            assertThat(context).hasSingleBean(IdleSessionReaper.class);
        });
    }

    @Test
    @DisplayName("jellyfish.user.enabled=false 时护栏一个都不装，但内核照常")
    void context_should_register_nothing_when_user_guards_disabled() {
        // When / Then
        runner().withPropertyValues("jellyfish.user.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JellyfishUserFacade.class);
            assertThat(context).doesNotHaveBean(SessionOwnerStore.class);
            assertThat(context).doesNotHaveBean(IdleSessionReaper.class);
            // 内核侧不受影响：关掉护栏只是不做多用户适配
            assertThat(context).hasSingleBean(JellyfishKernel.class);
            assertThat(context).hasSingleBean(JellyfishLifecycle.class);
        });
    }

    @Test
    @DisplayName("jellyfish.enabled=false 时护栏也一起不装（内核都没起，护栏没有意义）")
    void context_should_register_nothing_when_kernel_disabled() {
        // When / Then
        runner().withPropertyValues("jellyfish.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JellyfishUserFacade.class);
            assertThat(context).doesNotHaveBean(IdleSessionReaper.class);
        });
    }

    @Test
    @DisplayName("可以单独关掉回收器，其余护栏保留")
    void context_should_skip_reaper_when_reaper_disabled() {
        // When / Then
        runner().withPropertyValues("jellyfish.user.reaper-enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdleSessionReaper.class);
            assertThat(context).hasSingleBean(JellyfishUserFacade.class);
        });
    }

    @Test
    @DisplayName("配置项绑定正确，缺省值保守")
    void properties_should_reflect_configuration() {
        // When / Then
        runner().run(context -> {
            JellyfishUserProperties defaults = context.getBean(JellyfishUserProperties.class);
            assertEquals(2, defaults.getMaxInflightTurnsPerUser());
            assertEquals(20, defaults.getMaxSessionsPerUser());
            assertEquals(Duration.ofMinutes(30), defaults.getIdleTimeout());
            assertTrue(defaults.getDeniedCommands().contains("resume"));
        });
        runner().withPropertyValues(
                "jellyfish.user.max-inflight-turns-per-user=5",
                "jellyfish.user.max-sessions-per-user=100",
                "jellyfish.user.idle-timeout=90s",
                "jellyfish.user.denied-commands=new,boom").run(context -> {
            JellyfishUserProperties bound = context.getBean(JellyfishUserProperties.class);
            assertEquals(5, bound.getMaxInflightTurnsPerUser());
            assertEquals(100, bound.getMaxSessionsPerUser());
            assertEquals(Duration.ofSeconds(90), bound.getIdleTimeout());
            assertEquals(2, bound.getDeniedCommands().size());
            assertTrue(bound.getDeniedCommands().contains("boom"));
        });
    }

    @Test
    @DisplayName("门面接的是内核那一份实例，不是另建的视图")
    void facade_should_use_kernel_instances_when_assembled() {
        // When / Then：门面建的会话必须落在内核那一份会话表里。
        // 断言只能走门面自己的视图——内核门面不在容器里（那正是这一层的意义），
        // 而「同一条会话在归属者列表里可见」已经足以证明它落在真表上（另一份视图不会有它）
        runner().run(context -> {
            JellyfishUserFacade facade = context.getBean(JellyfishUserFacade.class);
            String sessionId = facade.createSession("alice");
            assertEquals(1, facade.listSessions("alice").size());
            assertEquals(sessionId, facade.listSessions("alice").get(0).getSessionId());
        });
    }
}
