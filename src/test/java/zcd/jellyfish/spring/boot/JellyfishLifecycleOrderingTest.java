package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.di.JellyfishRuntime;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * {@link JellyfishLifecycle} 与其它 {@code SmartLifecycle} 的相对顺序测试——<b>必须用真容器</b>。
 * <p>
 * 要保证的性质只有一条：内核早于 Web 容器就绪、晚于它停止。否则请求会打进一个已经关掉的线程池，
 * 而表现是「偶发地打不通」。这条性质由 Spring 的两条规则合成——启动按 phase 升序、停止按降序——
 * 因此 mock 掉容器是证明不了的，只有真起一次上下文、看事件序列才算数。
 * <p>
 * 记录方式值得说明：内核生命周期自己不产生「事件」，它的可观测动作是 {@code bootstrap()} 与
 * {@code shutdown()}。因此这里把这两个动作也接到同一个序列上（{@code doAnswer} 往序列里追加），
 * 于是「内核启动」「Web 启动」变成同一根时间轴上的两个点，先后可以直接比较。
 * <p>
 * 假 Web 生命周期的 phase 取 {@code Integer.MAX_VALUE - 1}（Boot 2.x 里 WebServerStartStopLifecycle
 * 的取值），因此本用例同时钉住了「内核的 phase 比它小」这个结论。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JellyfishLifecycle 与 Web 容器的相对顺序")
class JellyfishLifecycleOrderingTest {

    @Mock
    private JellyfishRuntime runtime;

    @Mock
    private RuntimeInfoHolder runtimeInfoHolder;

    @Mock
    private AgentHarness agentHarness;

    @Test
    @DisplayName("内核比 Web 容器先启动、后停止")
    void lifecycle_should_start_before_web_server_and_stop_after_it() {
        // Given
        OrderingConfig.Recorder recorder = new OrderingConfig.Recorder();
        lenient().when(runtime.runtimeInfoHolder()).thenReturn(runtimeInfoHolder);
        lenient().when(runtime.agentHarness()).thenReturn(agentHarness);
        doAnswer(invocation -> {
            recorder.getEvents().add("kernel:start");
            return null;
        }).when(agentHarness).bootstrap();
        doAnswer(invocation -> {
            recorder.getEvents().add("kernel:stop");
            return null;
        }).when(agentHarness).shutdown();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("jellyfishRuntime", runtime);
        context.getBeanFactory().registerSingleton("jellyfishProperties", new JellyfishProperties());
        context.getBeanFactory().registerSingleton("recorder", recorder);
        context.register(OrderingConfig.class);

        // When
        context.refresh();
        List<String> afterStart = copy(recorder.getEvents());
        context.close();

        // Then
        assertEquals(Arrays.asList("kernel:start", "web:start"), afterStart);
        assertEquals(Arrays.asList("kernel:start", "web:start", "web:stop", "kernel:stop"),
                recorder.getEvents());
    }

    /**
     * 复制一份事件序列：记录器本身是活的，直接持有它会在断言前被后续事件改写。
     *
     * @param events 记录器里的当前序列
     * @return 不可变副本
     */
    private static List<String> copy(List<String> events) {
        return Collections.unmodifiableList(new CopyOnWriteArrayList<String>(events));
    }

    /**
     * 顺序用例的装配：内核生命周期 + 一个模拟 Web 容器的生命周期，两者写同一个记录器。
     * <p>
     * 记录器由用例自己注册成单例（而不是在这里声明成 {@code @Bean}），因为内核动作也要写进它——
     * 那需要测试先拿到同一个实例去装配 {@code doAnswer}。
     *
     * @author zcd
     */
    @Configuration(proxyBeanMethods = false)
    static class OrderingConfig {

        /**
         * 被测的内核生命周期。
         *
         * @param runtime    内核运行时
         * @param properties 配置
         * @return 内核生命周期
         */
        @Bean
        public JellyfishLifecycle jellyfishLifecycle(JellyfishRuntime runtime, JellyfishProperties properties) {
            return new JellyfishLifecycle(runtime, properties);
        }

        /**
         * 模拟 Web 容器的生命周期，phase 与 Boot 的 Web 容器同量级。
         *
         * @param recorder 事件记录器
         * @return 假 Web 生命周期
         */
        @Bean
        public SmartLifecycle fakeWebServerLifecycle(Recorder recorder) {
            return new FakeLifecycle("web", Integer.MAX_VALUE - 1, recorder);
        }

        /**
         * 事件记录器：按发生顺序记下每次启动 / 停止。
         *
         * @author zcd
         */
        static class Recorder {

            /** 事件序列。 */
            private final List<String> events = new CopyOnWriteArrayList<String>();

            /**
             * 获取事件序列。
             *
             * @return 事件序列（活对象，需要快照请自行复制）
             */
            List<String> getEvents() {
                return events;
            }
        }

        /**
         * 只记录、不做事的生命周期。
         *
         * @author zcd
         */
        static class FakeLifecycle implements SmartLifecycle {

            /** 名字，用于区分。 */
            private final String name;

            /** 阶段。 */
            private final int phase;

            /** 事件记录器。 */
            private final Recorder recorder;

            /** 是否在运行。 */
            private volatile boolean running;

            /**
             * 构造假生命周期。
             *
             * @param name     名字
             * @param phase    阶段
             * @param recorder 记录器
             */
            FakeLifecycle(String name, int phase, Recorder recorder) {
                this.name = name;
                this.phase = phase;
                this.recorder = recorder;
            }

            @Override
            public void start() {
                recorder.getEvents().add(name + ":start");
                running = true;
            }

            @Override
            public void stop() {
                recorder.getEvents().add(name + ":stop");
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return phase;
            }
        }
    }
}
