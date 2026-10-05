package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.config.AppConfig;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishProperties} 的单元测试：缺省值、绑定后的取值，以及到 {@link AppConfig} 的映射。
 * <p>
 * 重点是「yml 只给路径」这条分工在代码里是否真的成立——所以断言落在路径与扫描目录上，
 * 而不是在内核内容上。
 *
 * @author zcd
 */
@DisplayName("JellyfishProperties 配置项")
class JellyfishPropertiesTest {

    @Test
    @DisplayName("什么都不配时，路径与插件目录取内核自己的缺省值")
    void toAppConfig_should_use_kernel_defaults_when_nothing_configured() {
        // When
        AppConfig appConfig = new JellyfishProperties().toAppConfig();

        // Then：与内核 config.json 的缺省一致，同一个进程里 CLI 与 Spring 应用看到同一份配置
        assertEquals(AppConfig.DEFAULT_PROCESS_NAME, appConfig.getProcessName());
        assertTrue(appConfig.getModel().getGlobalPath().endsWith(".jellyfish/models.json"),
                "实际：" + appConfig.getModel().getGlobalPath());
        assertTrue(appConfig.getModel().getProjectPath().endsWith(".jellyfish/models.json"),
                "实际：" + appConfig.getModel().getProjectPath());
        assertTrue(appConfig.getAgent().getGlobalPath().endsWith(".jellyfish/agents.json"));
        assertTrue(appConfig.getJellyfish().getGlobalPath().endsWith(".jellyfish/jellyfish.json"));
        assertEquals(1, appConfig.getPlugins().getRoots().size());
        assertTrue(appConfig.getPlugins().getRoots().get(0).endsWith(".jellyfish/plugins/"));
    }

    @Test
    @DisplayName("配了的值如实进 AppConfig，路径支持 classpath 前缀")
    void toAppConfig_should_map_configured_values_when_set() {
        // Given
        JellyfishProperties properties = new JellyfishProperties();
        properties.setProcessName("my-app");
        properties.setModels(JellyfishProperties.ConfigFiles.of("", "classpath:jellyfish/models.json"));
        properties.setAgents(JellyfishProperties.ConfigFiles.of("", "classpath:jellyfish/agents.json"));
        properties.setSettings(JellyfishProperties.ConfigFiles.of("", "classpath:jellyfish/jellyfish.json"));
        properties.getPlugins().setRoots(Arrays.asList("/opt/jellyfish/plugins", "/opt/extra/plugins"));

        // When
        AppConfig appConfig = properties.toAppConfig();

        // Then
        assertEquals("my-app", appConfig.getProcessName());
        assertEquals("", appConfig.getModel().getGlobalPath());
        assertEquals("classpath:jellyfish/models.json", appConfig.getModel().getProjectPath());
        assertEquals("classpath:jellyfish/agents.json", appConfig.getAgent().getProjectPath());
        assertEquals("classpath:jellyfish/jellyfish.json", appConfig.getJellyfish().getProjectPath());
        assertEquals(Arrays.asList("/opt/jellyfish/plugins", "/opt/extra/plugins"),
                appConfig.getPlugins().getRoots());
    }

    @Test
    @DisplayName("把路径设成 null 时按「未配置」处理，而不是留一个 null 给内核")
    void configFiles_should_treat_null_as_unconfigured_when_set() {
        // Given
        JellyfishProperties.ConfigFiles files = JellyfishProperties.ConfigFiles.of("a.json", "b.json");

        // When
        files.setGlobalPath(null);
        files.setProjectPath(null);

        // Then：空串在内核那里等于「未配置」——既不读文件也不发告警，null 则会炸在别处
        assertEquals("", files.getGlobalPath());
        assertEquals("", files.getProjectPath());
        assertNotNull(files.toConfigPaths());
        assertEquals("", files.toConfigPaths().getGlobalPath());
    }

    @Test
    @DisplayName("把插件目录设成 null 时得到空列表，交给内核回退缺省目录")
    void plugins_should_fall_back_when_roots_set_to_null() {
        // Given
        JellyfishProperties properties = new JellyfishProperties();

        // When
        properties.getPlugins().setRoots(null);

        // Then
        assertNotNull(properties.getPlugins().getRoots());
        assertTrue(properties.getPlugins().getRoots().isEmpty());
        assertTrue(properties.toAppConfig().getPlugins().getRoots().isEmpty());
    }

    @Test
    @DisplayName("开关类字段的缺省值：启用 starter、不挂审批、启动时拉起内核")
    void switches_should_have_expected_defaults_when_nothing_configured() {
        // Given
        JellyfishProperties properties = new JellyfishProperties();

        // When / Then
        assertTrue(properties.isEnabled());
        // 缺省不挂审批：未挂载时「需要审批」的调用立即按拒绝处理，比挂上却没人裁决、
        // 白等 120 秒更容易排查
        assertFalse(properties.getApproval().isEnabled());
        assertTrue(properties.getLifecycle().isBootstrapOnStart());
        assertFalse(properties.getRuntime().isInteractive());
    }
}
