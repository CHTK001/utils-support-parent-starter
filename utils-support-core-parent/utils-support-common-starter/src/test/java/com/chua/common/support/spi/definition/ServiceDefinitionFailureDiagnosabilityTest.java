package com.chua.common.support.spi.definition;

import com.chua.common.support.spi.autowire.AutoServiceAutowire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI 实例化失败必须可诊断。
 *
 * <p>{@code ServiceDefinition#newInstance} 有三条路径：精确构造器匹配 → 逐个构造器尝试
 * → 无参兜底。历史上后两条里的 {@code catch (Exception ignored)} 完全静默，于是
 * 「构造器签名不匹配」与「构造过程抛异常」两类失败都只表现为返回 {@code null}，
 * 调用方只能报一句「未注册 / 实例化失败」——FTPS 就因此被长期误报为
 * {@code Server SPI 未注册}，真因（自签证书未做预处理）被掩盖了整整一轮排查。</p>
 *
 * <p>本测试钉住修复后的语义：失败原因必须能从 {@code getFailureMessage()} 取到，
 * 且包含足以定位的场景描述与原始异常信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class ServiceDefinitionFailureDiagnosabilityTest {

    /**
     * 构造过程必然抛异常的实现。
     */
    public static class ThrowingOnConstruct {

        /**
         * 构造即失败。
         *
         * @param marker 任意参数，用于匹配 (String) 构造器
         */
        public ThrowingOnConstruct(String marker) {
            throw new IllegalStateException("BOOM-from-constructor:" + marker);
        }
    }

    /**
     * 只有无参构造的实现（用于验证「实参类型不匹配」与「成功不留痕」两条路径）。
     */
    public static class OnlyNoArg {

        /**
         * 无参构造。
         */
        public OnlyNoArg() {
            // 无需实现
        }
    }

    /**
     * 私有构造且无无参构造：任何实参都无法实例化。
     */
    public static class NoCtorAtAll {

        /**
         * 私有带参构造，外部无法通过反射实例化。
         *
         * @param value 任意值
         */
        private NoCtorAtAll(String value) {
            throw new IllegalStateException("不应被调用: " + value);
        }
    }

    private static ServiceDefinition definitionOf(Class<?> implClass) {
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName(implClass.getSimpleName());
        definition.setImplClass(implClass);
        return definition;
    }

    @Test
    @DisplayName("构造器抛异常时，失败原因必须可取且指明是哪个构造器")
    void constructorThrowIsDiagnosable() {
        ServiceDefinition definition = definitionOf(ThrowingOnConstruct.class);

        // 两条路径都要覆盖：精确匹配命中时 ClassUtils 会把构造异常吞成 null；
        // 若走兜底路径则本类包装后 rethrow。两者都必须留下可读原因。
        try {
            Object instance = definition.newInstance(AutoServiceAutowire.INSTANCE, "arg1");
            assertNull(instance, "兜底路径构造失败应返回 null（保持既有语义，不改变控制流）");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getCause(), "包装后的异常应保留原始 cause");
        }

        String reason = definition.getFailureMessage();
        assertNotNull(reason, "失败原因必须可取，否则调用方只能报「实例化失败」");
        assertTrue(reason.contains("构造器"),
                "失败原因应指明是哪个构造器，实际: " + reason);
    }

    @Test
    @DisplayName("失败原因必须带上构造器体里的真实根因，而不只是「实例化失败」")
    void rootCauseReachesFailureMessage() {
        ServiceDefinition definition = definitionOf(ThrowingOnConstruct.class);

        try {
            definition.newInstance(AutoServiceAutowire.INSTANCE, "ROOTCAUSE-MARK-91");
        } catch (RuntimeException expected) {
            // 兜底路径会包装后 rethrow，此处不改变控制流，仅容忍
        }

        String reason = definition.getFailureMessage();
        assertNotNull(reason, "失败原因必须可取");
        assertTrue(reason.contains("ROOTCAUSE-MARK-91"),
                "失败原因必须包含构造器体抛出的原始信息（BOOM-from-constructor:ROOTCAUSE-MARK-91），"
                        + "否则排查时仍只能看到「实例化失败」而无法定位；实际: " + reason);
        assertTrue(!reason.contains("InvocationTargetException"),
                "不应把反射包装层当根因报出，实际: " + reason);
    }

    @Test
    @DisplayName("实参类型与任何构造器都不匹配时，失败原因应说明所有构造器都无法实例化")
    void noMatchingConstructorIsDiagnosable() {
        ServiceDefinition definition = definitionOf(NoCtorAtAll.class);

        // 该类没有任何构造器接收 Integer，精确匹配必然落空，无参兜底也不存在
        Object instance = definition.newInstance(AutoServiceAutowire.INSTANCE, 12345);

        assertNull(instance, "无法实例化时应返回 null");
        String reason = definition.getFailureMessage();
        assertNotNull(reason, "返回 null 时必须有失败原因");
        assertTrue(reason.contains("构造器") || reason.contains("实参"),
                "失败原因应指向构造器匹配或实参问题，实际: " + reason);
    }

    @Test
    @DisplayName("从未失败过时 getFailureMessage() 返回 null")
    void noFailureMeansNoMessage() {
        ServiceDefinition definition = definitionOf(OnlyNoArg.class);

        assertNull(definition.getFailureMessage(), "未失败过不应返回失败原因");
    }

    @Test
    @DisplayName("实例化成功后不应残留失败原因，避免误报")
    void successLeavesNoStaleFailure() {
        ServiceDefinition definition = definitionOf(OnlyNoArg.class);

        Object instance = definition.newInstance(AutoServiceAutowire.INSTANCE);

        assertNotNull(instance, "无参构造应成功");
        assertNull(definition.getFailureMessage(), "成功路径不应记录失败原因");
    }

    @Test
    @DisplayName("保留首个失败原因：后续派生失败不覆盖根因")
    void firstFailureWins() {
        ServiceDefinition definition = definitionOf(ThrowingOnConstruct.class);

        // 多次尝试，只应保留第一次的原因
        definition.newInstance(AutoServiceAutowire.INSTANCE, "first");
        String first = definition.getFailureMessage();
        definition.newInstance(AutoServiceAutowire.INSTANCE, "second");

        assertEquals(first, definition.getFailureMessage(), "首个失败原因不应被后续尝试覆盖");
    }
}
