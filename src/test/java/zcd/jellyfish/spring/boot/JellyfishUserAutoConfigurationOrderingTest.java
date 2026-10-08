package zcd.jellyfish.spring.boot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import zcd.jellyfish.spring.boot.user.JellyfishUserAutoConfiguration;
import zcd.jellyfish.spring.boot.user.JellyfishUserFacade;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多用户护栏那一半「什么时候装」的顺序契约——把原来只能靠字母序侥幸成立的关系变成可验证的事实。
 * <p>
 * <b>被守的性质</b>：{@link JellyfishUserAutoConfiguration} 用 {@code @ConditionalOnBean(JellyfishKernel.class)}
 * 决定装不装，而这个条件要求内核那一半<b>先</b>装配好。自动配置之间的顺序若由「谁在
 * {@code spring.factories} 里排前面」决定，那就是没人能预料的事；{@code @AutoConfigureAfter} 是唯一能
 * 把它变成确定事实的声明。
 * <p>
 * <b>为什么要专门一个用例守它</b>：这条性质在<b>今天</b>恰好也成立——两个类的全限定名按字母序排，
 * 内核那一半（{@code …boot.JellyfishAutoConfiguration}）正好在护栏那一半
 * （{@code …boot.user.JellyfishUserAutoConfiguration}）之前。于是「把注解删掉」在当前命名下不会
 * 立刻出事，谁也不会发现契约已经没了；等到有人挪了包、改了类名，护栏就变成时有时无。
 * 因此这里做三件事：<b>把契约本身断言下来</b>（反射读注解，删掉就红——<b>它是唯一能守住这条契约的
 * 用例，实测删掉注解后只有它变红</b>）、<b>证明这个注解真的会改变处理顺序</b>（两个探针类，
 * 字母序与约束方向相反，因此观测到的顺序只可能来自那条声明）、以及<b>把顺序喂成反的</b>看护栏是否仍在线。
 * <p>
 * 最后那一条<b>不</b>守 {@code @AutoConfigureAfter}（实测：删掉注解它照样绿，因为字母序替它兜住了），
 * 它守的是「这层装不装」的判据与顺序敏感性——别把它当成了契约的守卫。
 * <p>
 * <b>为什么不用 Boot 的排序器直接验</b>：{@code AutoConfigurationSorter} 在 2.7 里是包私有的，
 * 测试要调它就得写进 {@code org.springframework.boot.autoconfigure} 包，那等于把测试绑死在 Boot 的
 * 内部实现上。改用「真容器 + 观测处理顺序」，验的是同一件事，且只依赖公开 API。
 *
 * @author zcd
 */
@DisplayName("多用户护栏的自动配置顺序契约")
class JellyfishUserAutoConfigurationOrderingTest {

    /** 插件扫描目录，指向临时目录以免扫到使用者的真实插件。 */
    @TempDir
    Path pluginsRoot;

    /**
     * 清空上一个用例的记录。
     */
    @BeforeEach
    void setUp() {
        OrderRecorder.clear();
    }

    @Test
    @DisplayName("契约在：护栏那一半声明了 @AutoConfigureAfter（删掉它这个用例就红）")
    void userAutoConfiguration_should_declareAfterKernel() {
        // When
        AutoConfigureAfter after = JellyfishUserAutoConfiguration.class.getAnnotation(AutoConfigureAfter.class);

        // Then：注解在，且指向内核那一半
        assertThat(after).as("JellyfishUserAutoConfiguration 必须声明 @AutoConfigureAfter").isNotNull();
        assertThat(Arrays.asList(after.value()))
                .as("必须显式声明「排在内核那一半之后」")
                .contains(JellyfishAutoConfiguration.class);
    }

    @Test
    @DisplayName("契约的判据也在：@ConditionalOnBean 指向内核持有者（这是它需要顺序保证的原因）")
    void userAutoConfiguration_should_beConditionalOnKernelHolder() {
        // When
        ConditionalOnBean conditional = JellyfishUserAutoConfiguration.class.getAnnotation(ConditionalOnBean.class);

        // Then：条件依赖 JellyfishKernel —— 少了顺序保证，它就会在「内核还没装配」时求值
        assertThat(conditional).as("@ConditionalOnBean 是这一层装不装的判据").isNotNull();
        assertThat(Arrays.asList(conditional.value())).contains(JellyfishKernel.class);
    }

    @Test
    @DisplayName("@AutoConfigureAfter 真的会改变处理顺序（字母序在前的那一个被排到了后面）")
    void autoConfigureAfter_should_reorder_whenAlphabeticalOrderContradicts() {
        // Given：探针 A 的类名按字母序在前，却声明「排在探针 Z 之后」——
        // 两者方向相反，因此观测到的顺序只可能来自这条声明
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProbeA.class, ProbeZ.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // Then：Z 先被处理。字母序会给出 A、Z，正是这条声明把它纠正了过来
                    assertEquals(Arrays.asList("Z", "A"), OrderRecorder.snapshot(),
                            "带 @AutoConfigureAfter 的那个必须后处理");
                });
    }

    @Test
    @DisplayName("把两个真实配置类按反序喂进去，护栏照样在线（顺序敏感关系不依赖给定顺序；但这条不守注解）")
    void userFacade_should_beRegistered_whenAutoConfigurationsGivenInReverseOrder() {
        // Given：故意把护栏那一半写在前面——模拟「注册顺序变化」这种报告担心的场景
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JellyfishUserAutoConfiguration.class,
                        JellyfishAutoConfiguration.class))
                .withPropertyValues(
                        "jellyfish.models.global-path=",
                        "jellyfish.models.project-path=",
                        "jellyfish.agents.global-path=",
                        "jellyfish.agents.project-path=",
                        "jellyfish.settings.global-path=",
                        "jellyfish.settings.project-path=",
                        "jellyfish.plugins.roots=" + pluginsRoot,
                        "jellyfish.lifecycle.bootstrap-on-start=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    // Then：内核与护栏都在，且护栏真的被装上（不是「少装一层」那种静默故障）
                    assertThat(context).hasSingleBean(JellyfishKernel.class);
                    assertThat(context).hasSingleBean(JellyfishUserFacade.class);
                });
    }

    /**
     * 处理顺序的记录器：探针的 {@code @Bean} 方法实例化时按名字记一行。
     * <p>
     * 单例默认在容器刷新末尾按注册顺序实例化，而注册顺序就是配置类的解析顺序（即自动配置的排序结果），
     * 因此这份记录的顺序就是「谁先被处理」。
     *
     * @author zcd
     */
    static final class OrderRecorder {

        /** 已处理的探针名字。 */
        private static final List<String> NAMES = new ArrayList<String>();

        private OrderRecorder() {
        }

        /**
         * 记下一个探针。
         *
         * @param name 探针名字
         */
        static synchronized void record(String name) {
            NAMES.add(name);
        }

        /**
         * 清空记录。
         */
        static synchronized void clear() {
            NAMES.clear();
        }

        /**
         * 取当前记录的副本。
         *
         * @return 名字列表
         */
        static synchronized List<String> snapshot() {
            return new ArrayList<String>(NAMES);
        }
    }

    /**
     * 探针 A：类名按字母序在 Z 之前，但声明「排在 Z 之后」。
     *
     * @author zcd
     */
    @AutoConfiguration
    @AutoConfigureAfter(ProbeZ.class)
    static class ProbeA {

        /**
         * 记录自己的处理。
         *
         * @return 一个无害的 Bean
         */
        @Bean
        String probeA() {
            OrderRecorder.record("A");
            return "A";
        }
    }

    /**
     * 探针 Z：不带任何顺序声明，只作为 A 的约束目标。
     *
     * @author zcd
     */
    @AutoConfiguration
    static class ProbeZ {

        /**
         * 记录自己的处理。
         *
         * @return 一个无害的 Bean
         */
        @Bean
        String probeZ() {
            OrderRecorder.record("Z");
            return "Z";
        }
    }
}
