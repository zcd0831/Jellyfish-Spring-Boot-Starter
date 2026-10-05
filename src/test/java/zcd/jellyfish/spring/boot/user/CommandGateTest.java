package zcd.jellyfish.spring.boot.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandGate} 的单元测试。
 * <p>
 * 它守的是「命令参数不受归属校验保护」这个口子：{@code /delete <别人的会话 id>} 里的那个 id
 * 从不经过本层的归属检查，闸门是唯一能拦住它的地方。因此这里逐条钉住缺省名单里的每个命令。
 *
 * @author zcd
 */
@DisplayName("CommandGate 命令闸门")
class CommandGateTest {

    /** 被测对象。 */
    private final CommandGate gate = new CommandGate(new JellyfishUserProperties());

    @ParameterizedTest
    @DisplayName("会碰进程级状态或别人会话的命令一律拒绝")
    @ValueSource(strings = {"/new", "/resume", "/delete s1", "/rm s1", "/session", "/sessions", "/reload"})
    void check_should_reject_when_command_is_in_deny_list(String commandLine) {
        // When / Then
        assertThrows(CommandNotAllowedException.class, () -> gate.check(commandLine, "s1"));
    }

    @Test
    @DisplayName("拒绝时带上命令名，便于业务侧记日志")
    void check_should_report_command_name_when_rejected() {
        // When
        CommandNotAllowedException thrown = assertThrows(CommandNotAllowedException.class,
                () -> gate.check("/delete other-users-session", "s1"));

        // Then
        assertEquals("delete", thrown.getCommandName());
    }

    @Test
    @DisplayName("必须带会话的命令在没带会话时被拒（不带会话它改的是进程级默认值）")
    void check_should_reject_when_session_scoped_command_has_no_session() {
        // When / Then
        assertThrows(CommandNotAllowedException.class, () -> gate.check("/model openai/gpt-4o", null));
        assertThrows(CommandNotAllowedException.class, () -> gate.check("/agent scout", null));
    }

    @Test
    @DisplayName("同一个命令带上会话就放行")
    void check_should_allow_when_session_scoped_command_has_session() {
        // When / Then
        gate.check("/model openai/gpt-4o", "s1");
        gate.check("/agent scout", "s1");
    }

    @Test
    @DisplayName("会话内安全的命令放行")
    void check_should_allow_when_command_is_session_scoped_and_read_only() {
        // When / Then
        gate.check("/status", "s1");
        gate.check("/usage", "s1");
        gate.check("/compact preview", "s1");
        gate.check("/help", null);
    }

    @Test
    @DisplayName("不是命令的输入直接放行，交给内核报「未知命令」")
    void check_should_allow_when_input_is_not_a_command() {
        // When / Then
        gate.check("你好", "s1");
        gate.check("", "s1");
        gate.check(null, "s1");
    }

    @Test
    @DisplayName("取出命令名：去前缀、去参数、容忍多余空白")
    void nameOf_should_extract_command_name_when_parsing() {
        // When / Then
        assertEquals("compact", gate.nameOf("/compact preview"));
        assertEquals("status", gate.nameOf("  /status  "));
        assertEquals("status", gate.nameOf("/status"));
        assertEquals("", gate.nameOf("no-slash"));
        assertEquals("", gate.nameOf("/"));
        assertEquals("", gate.nameOf(null));
    }

    @Test
    @DisplayName("前缀在参数里出现时不影响判定（只看第一个词）")
    void check_should_judge_first_token_when_arguments_contain_slash() {
        // When / Then：/model 本身允许，参数里的斜杠不该被当成命令
        gate.check("/model anthropic/claude-3", "s1");
    }

    @Test
    @DisplayName("名单可配：清空拒绝名单后命令被放行")
    void check_should_allow_when_deny_list_is_empty() {
        // Given
        JellyfishUserProperties properties = new JellyfishUserProperties();
        properties.setDeniedCommands(java.util.Collections.<String>emptyList());
        properties.setSessionRequiredCommands(java.util.Collections.<String>emptyList());
        CommandGate permissive = new CommandGate(properties);

        // When / Then
        permissive.check("/new", null);
        permissive.check("/delete s1", null);
    }

    @Test
    @DisplayName("诊断用清单如实反映配置")
    void lists_should_reflect_configuration() {
        // Given
        JellyfishUserProperties properties = new JellyfishUserProperties();
        properties.setDeniedCommands(java.util.Arrays.asList("new", "boom"));

        // When
        CommandGate custom = new CommandGate(properties);

        // Then
        assertTrue(custom.deniedCommands().contains("new"));
        assertTrue(custom.deniedCommands().contains("boom"));
        assertTrue(custom.sessionRequiredCommands().contains("model"));
    }
}
