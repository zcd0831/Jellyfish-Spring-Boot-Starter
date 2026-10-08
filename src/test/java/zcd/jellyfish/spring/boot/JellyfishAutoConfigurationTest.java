package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import zcd.jellyfish.infra.config.AppConfig;
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

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishAutoConfiguration} 的装配契约测试：用真内核、真装配，但不起 Web 容器。
 * <p>
 * <b>为什么用 {@code ApplicationContextRunner}</b>：这是 Boot 官方给自动配置准备的测试载体——
 * 它按不同属性组合反复起停上下文，且不启动 Web 容器。这里要验的正是「在给定属性下装配出什么」。
 * <p>
 * <b>为什么每个用例都把配置路径清空</b>：缺省路径指向 {@code ~/.jellyfish/}，那是使用者真实的环境。
 * 单测碰它既不可重复、又会互相污染。清空之后内核把四份配置都当成「未配置」（只告警、不中断启动），
 * 因此这是一份合法输入。插件扫描目录指向 JUnit 的临时目录。
 * <p>
 * <b>内核真的会启动</b>：{@code bootstrap-on-start=true} 的用例跑完整的 {@code AgentHarness.bootstrap()}
 * （事件通道、指标订阅、插件运行时、会话恢复）。上下文关闭时生命周期把它收敛——这一点也断言了，
 * 否则「测试跑完留下一堆后台线程」会静默累积。
 *
 * @author zcd
 */
@DisplayName("JellyfishAutoConfiguration 自动配置")
class JellyfishAutoConfigurationTest {

    /** 插件扫描目录，指向临时目录以免扫到使用者的真实插件。 */
    @TempDir
    Path pluginsRoot;

    /**
     * 构造测试载体：清空四份配置路径、插件目录指向临时目录、缺省不启动内核。
     * <p>
     * 「不启动内核」是缺省，因为大多数用例验的是装配（哪些 Bean 在），起一遍内核只会更慢；
     * 需要真启动的用例自己覆盖这一个属性。
     *
     * @return 测试载体
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JellyfishAutoConfiguration.class))
                .withPropertyValues(
                        "jellyfish.models.global-path=",
                        "jellyfish.models.project-path=",
                        "jellyfish.agents.global-path=",
                        "jellyfish.agents.project-path=",
                        "jellyfish.settings.global-path=",
                        "jellyfish.settings.project-path=",
                        "jellyfish.plugins.roots=" + pluginsRoot,
                        "jellyfish.lifecycle.bootstrap-on-start=false");
    }

    @Test
    @DisplayName("默认启用：装配出内核持有者、配置与生命周期")
    void context_should_expose_assembly_when_enabled() {
        // When / Then
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JellyfishKernel.class);
            assertThat(context).hasSingleBean(AppConfig.class);
            assertThat(context).hasSingleBean(JellyfishLifecycle.class);
        });
    }

    @Test
    @DisplayName("只暴露观测面；内核门面与「按会话操作」的那些都不进容器")
    void context_should_expose_observation_facades_only_when_enabled() {
        // When / Then
        runner().run(context -> {
            assertThat(context).hasSingleBean(LlmClientFactory.class);
            assertThat(context).hasSingleBean(ModelManager.class);
            assertThat(context).hasSingleBean(RuntimeConfig.class);
            assertThat(context).hasSingleBean(AgentManager.class);
            assertThat(context).hasSingleBean(SessionDefaults.class);
            assertThat(context).hasSingleBean(RuntimeInfoHolder.class);
            assertThat(context).hasSingleBean(HealthCheck.class);

            // 门面必须来自同一张对象图：转发错了会在这里露出来（否则会出现同一份索引的第二份视图）
            // ——这里用「与通过持有者取到的是同一个实例」来钉住它
            JellyfishKernel kernel = context.getBean(JellyfishKernel.class);
            assertThat(context.getBean(ModelManager.class)).isSameAs(kernel.runtime().modelManager());
            assertThat(context.getBean(HealthCheck.class)).isSameAs(kernel.runtime().healthCheck());
            assertThat(context.getBean(SessionDefaults.class)).isSameAs(kernel.runtime().sessionDefaults());
        });
    }

    @Test
    @DisplayName("内核门面不交给容器：任何 Bean 都拿不到「按 id 操作任意会话」的能力")
    void context_should_not_expose_kernel_facades_when_enabled() {
        // When / Then：这一条是这道边界的回归防线。往里加回任意一个，它都会红
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JellyfishRuntime.class);
            assertThat(context).doesNotHaveBean(ApprovalChannel.class);
            assertThat(context).doesNotHaveBean(AskChannel.class);
            assertThat(context).doesNotHaveBean(SessionManager.class);
            assertThat(context).doesNotHaveBean(ConversationService.class);
            assertThat(context).doesNotHaveBean(TurnRegistry.class);
            assertThat(context).doesNotHaveBean(ShellStreams.class);
            assertThat(context).doesNotHaveBean(RunEventBus.class);
            assertThat(context).doesNotHaveBean(CommandManager.class);
            assertThat(context).doesNotHaveBean(AgentHarness.class);
            assertThat(context).doesNotHaveBean(InputDirectives.class);
            assertThat(context).doesNotHaveBean(ExtensionRegistry.class);
            assertThat(context).doesNotHaveBean(PermissionManager.class);
            assertThat(context).doesNotHaveBean(ConversationCompactor.class);
            assertThat(context).doesNotHaveBean(EventChannel.class);
        });
    }

    @Test
    @DisplayName("jellyfish.enabled=false 时一个 Bean 都不装配（连对象图都不构造）")
    void context_should_register_nothing_when_disabled() {
        // When / Then
        runner().withPropertyValues("jellyfish.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JellyfishKernel.class);
            assertThat(context).doesNotHaveBean(AppConfig.class);
            assertThat(context).doesNotHaveBean(ModelManager.class);
            assertThat(context).doesNotHaveBean(JellyfishLifecycle.class);
        });
    }

    @Test
    @DisplayName("yml 里的路径与插件目录如实进 AppConfig")
    void appConfig_should_reflect_configured_paths_when_set() {
        // When / Then
        runner().withPropertyValues(
                "jellyfish.process-name=my-app",
                "jellyfish.models.project-path=classpath:jellyfish/models.json",
                "jellyfish.plugins.roots=/opt/a,/opt/b").run(context -> {
            AppConfig appConfig = context.getBean(AppConfig.class);
            assertEquals("my-app", appConfig.getProcessName());
            assertEquals("", appConfig.getModel().getGlobalPath());
            assertEquals("classpath:jellyfish/models.json", appConfig.getModel().getProjectPath());
            assertEquals(2, appConfig.getPlugins().getRoots().size());
            assertTrue(appConfig.getPlugins().getRoots().get(1).endsWith("b"));
        });
    }

    @Test
    @DisplayName("插件目录不存在时内核仍能启动（部署时目录可能还没建）")
    void context_should_start_kernel_when_plugin_directory_is_missing() {
        // Given：一个确定不存在的目录
        String missing = pluginsRoot.resolve("not-created-yet").toString();

        // When / Then
        runner().withPropertyValues("jellyfish.lifecycle.bootstrap-on-start=true",
                "jellyfish.plugins.roots=" + missing).run(context -> {
            assertThat(context).hasNotFailed();
            assertTrue(context.getBean(JellyfishLifecycle.class).isRunning(), "内核应当在运行");
        });
    }

    @Test
    @DisplayName("自动启动时内核真的起来了，上下文关闭后真的收敛了")
    void context_should_start_and_shutdown_kernel_when_bootstrap_on_start() {
        // Given
        AtomicReference<JellyfishLifecycle> lifecycleRef = new AtomicReference<JellyfishLifecycle>();

        // When
        runner().withPropertyValues("jellyfish.lifecycle.bootstrap-on-start=true").run(context -> {
            assertThat(context).hasNotFailed();
            JellyfishLifecycle lifecycle = context.getBean(JellyfishLifecycle.class);
            assertTrue(lifecycle.isRunning(), "上下文启动后内核应当在运行");
            lifecycleRef.set(lifecycle);
        });

        // Then：容器一关，内核就得收敛，否则测试进程里会留下一堆后台线程
        assertFalse(lifecycleRef.get().isRunning(), "上下文关闭后内核应当已收敛");
    }

    @Test
    @DisplayName("接入方自己注册 ExecutorService 不会与内核冲突")
    void context_should_tolerate_existing_executor_bean_when_registered() {
        // When / Then
        runner().withUserConfiguration(CustomExecutorConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JellyfishKernel.class);
            assertThat(context).hasSingleBean(ExecutorService.class);
        });
    }

    /**
     * 接入方自定义线程池的装配。
     * <p>
     * 这个用例存在的理由：内核内部有一个流式请求用的 {@code ExecutorService}，但它是
     * {@code JellyfishAssembler} 里 {@code new} 出来的、<b>不是 Spring Bean</b>。
     * 若哪天有人把它注册成 Bean，「两个同类型候选」会让这里变红——正是我们想要的报警方式。
     *
     * @author zcd
     */
    @Configuration(proxyBeanMethods = false)
    static class CustomExecutorConfig {

        /**
         * 提供接入方的线程池。
         *
         * @return 线程池
         */
        @Bean
        public ExecutorService executor() {
            return Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "biz-pool");
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
