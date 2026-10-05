package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link JellyfishLifecycle} 的单元测试：启动顺序（写运行时信息 → 挂审批 → 启动内核）、
 * 失败收敛、停止顺序与幂等。
 * <p>
 * 这里全用 mock：要验的是「按什么顺序调了谁」，真起一遍内核只会让测试变慢变脆，却证明不了更多。
 * 与其它 {@code SmartLifecycle} 的相对顺序另有一个用真容器的用例（{@link JellyfishLifecycleOrderingTest}），
 * 因为那条性质由 Spring 的 phase 规则决定，mock 掉容器就等于把被测的性质抽走了。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JellyfishLifecycle 生命周期")
class JellyfishLifecycleTest {

    @Mock
    private JellyfishRuntime runtime;

    @Mock
    private RuntimeInfoHolder runtimeInfoHolder;

    @Mock
    private AgentHarness agentHarness;

    @Mock
    private ApprovalChannel approvalChannel;

    /**
     * 构造被测对象，并把内核门面接上 mock。
     * <p>
     * 两个桩用 {@code lenient}：不同用例走的分支不同（关掉自动启动的用例一个门面都不碰），
     * 严格模式会把「这个用例没用到那个桩」判成错误，而它与被测行为无关。
     *
     * @param approvalEnabled  是否启用审批通道
     * @param bootstrapOnStart 是否在启动时拉起内核
     * @return 生命周期宿主
     */
    private JellyfishLifecycle lifecycle(boolean approvalEnabled, boolean bootstrapOnStart) {
        JellyfishProperties properties = new JellyfishProperties();
        properties.getApproval().setEnabled(approvalEnabled);
        properties.getLifecycle().setBootstrapOnStart(bootstrapOnStart);
        lenient().when(runtime.runtimeInfoHolder()).thenReturn(runtimeInfoHolder);
        lenient().when(runtime.agentHarness()).thenReturn(agentHarness);
        lenient().when(runtime.approvalChannel()).thenReturn(approvalChannel);
        return new JellyfishLifecycle(runtime, properties);
    }

    @Test
    @DisplayName("启动时先写运行时信息，再启动内核（插件在 start() 里就要读到它）")
    void start_should_write_runtime_info_before_bootstrap_when_approval_disabled() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, true);
        ArgumentCaptor<RuntimeInfo> captor = ArgumentCaptor.forClass(RuntimeInfo.class);

        // When
        lifecycle.start();

        // Then
        InOrder order = inOrder(runtimeInfoHolder, agentHarness);
        order.verify(runtimeInfoHolder).set(captor.capture());
        order.verify(agentHarness).bootstrap();
        RuntimeInfo info = captor.getValue();
        assertEquals(RuntimeInfo.Shell.SERVER, info.getShell());
        assertFalse(info.hasUI());
        assertFalse(info.isInteractive());
        assertTrue(lifecycle.isRunning());
    }

    @Test
    @DisplayName("未启用审批时，运行时信息如实声明「不具备审批通道」")
    void start_should_report_approval_absent_when_not_enabled() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, true);
        ArgumentCaptor<RuntimeInfo> captor = ArgumentCaptor.forClass(RuntimeInfo.class);

        // When
        lifecycle.start();

        // Then：不能用 RuntimeInfo.server(...)——那个工厂恒报 true，会让插件以为审批可用，
        // 于是「需要写权限的工具」被注册出来，实际调用时白等 120 秒
        verify(runtimeInfoHolder).set(captor.capture());
        assertFalse(captor.getValue().supportsApproval());
    }

    @Test
    @DisplayName("启用审批时先挂通道再启动内核，且运行时信息声明具备审批通道")
    void start_should_attach_approval_before_bootstrap_when_enabled() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(true, true);
        ArgumentCaptor<RuntimeInfo> captor = ArgumentCaptor.forClass(RuntimeInfo.class);

        // When
        lifecycle.start();

        // Then
        InOrder order = inOrder(runtimeInfoHolder, approvalChannel, agentHarness);
        order.verify(runtimeInfoHolder).set(captor.capture());
        order.verify(approvalChannel).attach();
        order.verify(agentHarness).bootstrap();
        assertTrue(captor.getValue().supportsApproval());
    }

    @Test
    @DisplayName("把交互开关打开时，如实描述给插件")
    void start_should_report_interactive_when_configured() {
        // Given
        JellyfishProperties properties = new JellyfishProperties();
        properties.getRuntime().setInteractive(true);
        when(runtime.runtimeInfoHolder()).thenReturn(runtimeInfoHolder);
        when(runtime.agentHarness()).thenReturn(agentHarness);
        JellyfishLifecycle lifecycle = new JellyfishLifecycle(runtime, properties);
        ArgumentCaptor<RuntimeInfo> captor = ArgumentCaptor.forClass(RuntimeInfo.class);

        // When
        lifecycle.start();

        // Then
        verify(runtimeInfoHolder).set(captor.capture());
        assertTrue(captor.getValue().isInteractive());
    }

    @Test
    @DisplayName("关掉自动启动时只装配不启动，也不写运行时信息")
    void start_should_only_assemble_when_auto_start_disabled() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, false);

        // When
        lifecycle.start();

        // Then
        verifyNoInteractions(agentHarness);
        verifyNoInteractions(runtimeInfoHolder);
        assertFalse(lifecycle.isRunning());
    }

    @Test
    @DisplayName("内核启动失败时先收敛已起的资源，再把异常原样抛出去让上下文也起不来")
    void start_should_converge_and_rethrow_when_bootstrap_fails() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, true);
        doThrow(new IllegalStateException("插件目录不可读")).when(agentHarness).bootstrap();

        // When
        IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);

        // Then
        assertEquals("插件目录不可读", thrown.getMessage());
        verify(agentHarness).shutdown();
        assertFalse(lifecycle.isRunning());
    }

    @Test
    @DisplayName("启动失败后的收敛若也失败，仍然把启动异常抛出去（收敛失败不盖掉根因）")
    void start_should_keep_original_failure_when_convergence_fails() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, true);
        doThrow(new IllegalStateException("启动失败")).when(agentHarness).bootstrap();
        doThrow(new IllegalStateException("收敛也失败")).when(agentHarness).shutdown();

        // When
        IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);

        // Then
        assertEquals("启动失败", thrown.getMessage());
    }

    @Test
    @DisplayName("停止时先摘审批再关内核（让卡在审批上的回合以「通道已关闭」收敛）")
    void stop_should_detach_approval_then_shutdown_when_running() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(true, true);
        lifecycle.start();

        // When
        lifecycle.stop();

        // Then
        InOrder order = inOrder(approvalChannel, agentHarness);
        order.verify(approvalChannel).detach();
        order.verify(agentHarness).shutdown();
        assertFalse(lifecycle.isRunning());
    }

    @Test
    @DisplayName("未启用审批时停止不去动审批通道")
    void stop_should_not_touch_approval_when_not_enabled() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, true);
        lifecycle.start();

        // When
        lifecycle.stop();

        // Then
        verify(approvalChannel, never()).detach();
    }

    @Test
    @DisplayName("从没启动过就停止时什么都不做（内核没起，无处可关）")
    void stop_should_do_nothing_when_never_started() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(true, false);

        // When
        lifecycle.stop();

        // Then
        verifyNoInteractions(agentHarness);
        verifyNoInteractions(approvalChannel);
    }

    @Test
    @DisplayName("重复停止只关一次内核")
    void stop_should_be_idempotent_when_called_twice() {
        // Given
        JellyfishLifecycle lifecycle = lifecycle(false, true);
        lifecycle.start();

        // When
        lifecycle.stop();
        lifecycle.stop();

        // Then
        verify(agentHarness, times(1)).shutdown();
    }
}
