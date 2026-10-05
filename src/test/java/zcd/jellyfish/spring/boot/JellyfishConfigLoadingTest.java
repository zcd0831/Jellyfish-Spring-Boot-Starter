package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.model.ModelManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 「yml 只给路径、内容仍是内核格式」这条分工的端到端测试。
 * <p>
 * <b>为什么必须有它</b>：其它用例把配置路径清空了（为了不碰使用者的 {@code ~/.jellyfish}），
 * 于是内核真正的那条链路——读文件 → 环境变量占位符替换 → Jackson 绑定 → 合并 → 建索引——一步都没走到。
 * 那条链路恰恰是最容易出问题的地方：值类型靠「恰好一个可见构造器 + 编译期 {@code -parameters}」反序列化，
 * 一旦失真，表现是「文件写得出来、读回去却悄悄变成默认值」，而所有不读文件的用例照样全绿。
 * <p>
 * 因此这里写两份真配置，走完整的 {@code AgentHarness.bootstrap()}，再从门面上把内容读回来验。
 *
 * @author zcd
 */
@DisplayName("配置文件链路：yml 给路径、内容走内核格式")
class JellyfishConfigLoadingTest {

    /** 临时目录，放本次测试专用的配置文件与插件目录。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("models.json 与 agents.json 被真正读进来并建成索引")
    void bootstrap_should_load_models_and_agents_when_paths_configured() throws IOException {
        // Given：两份内核格式的配置，放在临时目录里
        Path models = write("models.json", "{\n"
                + "  \"defaultProvider\": \"openai\",\n"
                + "  \"defaultModel\": \"gpt-4o\",\n"
                + "  \"providers\": {\n"
                + "    \"openai\": {\n"
                + "      \"type\": \"openai\",\n"
                + "      \"apiKey\": \"test-key-not-a-secret\",\n"
                + "      \"models\": [\n"
                + "        { \"id\": \"gpt-4o\", \"name\": \"gpt-4o\", \"contextLength\": 128000,"
                + " \"maxOutputTokens\": 4096 }\n"
                + "      ]\n"
                + "    }\n"
                + "  }\n"
                + "}\n");
        Path agents = write("agents.json", "{\n"
                + "  \"agents\": {\n"
                + "    \"demo\": {\n"
                + "      \"description\": \"测试用只读 agent\",\n"
                + "      \"delegatable\": true,\n"
                + "      \"permissions\": { \"allowedTools\": [\"read_file\"] }\n"
                + "    }\n"
                + "  }\n"
                + "}\n");

        // When：真启动内核（配置只在 bootstrap() 里装载）
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JellyfishAutoConfiguration.class))
                .withPropertyValues(
                        "jellyfish.models.global-path=" + models,
                        "jellyfish.agents.global-path=" + agents,
                        "jellyfish.settings.global-path=",
                        "jellyfish.plugins.roots=" + tempDir.resolve("plugins"),
                        "jellyfish.lifecycle.bootstrap-on-start=true")
                .run(context -> {
                    // Then
                    assertThat(context).hasNotFailed();

                    ModelManager models1 = context.getBean(ModelManager.class);
                    assertNotNull(models1.findProvider("openai"), "provider 应当被读进来");
                    assertNotNull(models1.findModel("openai", "gpt-4o"), "model 应当被读进来");
                    // 默认模型也走了同一条链：没有它 resolveDefault() 会抛错
                    assertNotNull(models1.resolveDefault(), "默认模型应当解析得出来");

                    AgentManager agents1 = context.getBean(AgentManager.class);
                    AgentDefinition demo = agents1.find("demo");
                    assertNotNull(demo, "自定义 agent 应当被读进来");
                    assertThat(demo.getDescription()).isEqualTo("测试用只读 agent");
                    // agentId 由映射的 key 回填，而不是条目内部声明的——这条也在验反序列化对不对
                    assertThat(demo.getAgentId()).isEqualTo("demo");
                });
    }

    /**
     * 写一份配置文件。
     *
     * @param fileName 文件名
     * @param content  内容
     * @return 写入后的路径
     * @throws IOException 写文件失败
     */
    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
