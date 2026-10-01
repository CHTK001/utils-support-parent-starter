/**
 * 反射实例化失败可诊断性门禁。
 *
 * 背景：本类原先有多处「捕获异常后返回 null」，其中最致命的是 SPI 实例化路径。
 * ServiceDefinition 直接调用 ClassUtils.newInstance(...)，而该方法把构造器体抛出的
 * 异常吃掉后只返回 null，导致：
 *   - 调用方只能看到「实例化结果为 null」，拿不到任何原因；
 *   - SPI 装配失败在日志里表现为「注册了但没实例化」，无根因可查。
 * 这也是 FTPS 这类「同一 jar 在不同进程里时好时坏」的问题长期无法定位的直接原因。
 *
 * 本类只钉「补了可观测性、控制流与返回值不变」这一契约，不改变任何行为：
 *   1. 失败时能取回**最内层**异常（不是 InvocationTargetException 这种包装层）；
 *   2. 取回是一次性的，且成功后不残留（否则会读到上一次的陈旧原因）；
 *   3. 按线程隔离（否则并发下会读到别的线程的错误，诊断时追错方向）。
 */
import com.chua.common.support.utils.ClassUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例化失败可诊断性测试。
 *
 * @author CH
 * @since 4.0
 */
@DisplayName("反射实例化失败可诊断性")
class ClassUtilsInstantiationFailureTest {

    /** 构造器体抛出带 message 的异常，用于验证根因下钻 */
    static class ThrowingWithMessage {
        ThrowingWithMessage(String seed) {
            throw new IllegalStateException("根因标记-CM-77-" + seed);
        }
    }

    /** 构造器体抛出无 message 的异常，用于验证「只有类型名」的降级描述 */
    static class ThrowingWithoutMessage {
        ThrowingWithoutMessage() {
            throw new UnsupportedOperationException();
        }
    }

    /** 构造器体抛出带 cause 的异常，用于验证沿 cause 链继续下钻 */
    static class ThrowingWithCause {
        ThrowingWithCause() {
            throw new RuntimeException("外层包装", new java.io.IOException("内层根因-CM-88"));
        }
    }

    /** 正常类，用于回归：成功路径行为不得改变 */
    static class Healthy {
        private final String value;

        Healthy(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    @Test
    @DisplayName("newInstance(Constructor) 失败时能取回构造器体的根因，而不是包装层")
    void newInstanceByConstructorExposesRootCause() throws Exception {
        Constructor<ThrowingWithMessage> constructor = ThrowingWithMessage.class.getDeclaredConstructor(String.class);
        Object instance = ClassUtils.newInstance(constructor, "seed");

        assertNull(instance, "控制流不变：失败仍返回 null");
        ClassUtils.NewInstanceFailure failure = ClassUtils.consumeLastInstantiationFailure();
        assertNotNull(failure, "失败必须留下原因记录");
        assertNotNull(failure.reason(), "原因不能为 null");
        assertTrue(failure.reason().contains("根因标记-CM-77-seed"),
                "原因必须是构造器体里的真实异常消息，实际=" + failure.reason());
    }

    @Test
    @DisplayName("原因是根因类型而非 InvocationTargetException 包装层")
    void reasonIsRootTypeNotWrapper() throws Exception {
        Constructor<ThrowingWithMessage> constructor = ThrowingWithMessage.class.getDeclaredConstructor(String.class);
        ClassUtils.newInstance(constructor, "x");

        String reason = ClassUtils.consumeLastInstantiationFailure().reason();
        assertTrue(reason.contains(IllegalStateException.class.getName()),
                "应下钻到 IllegalStateException，实际=" + reason);
        assertTrue(!reason.contains("InvocationTargetException"),
                "不应把包装层当根因，实际=" + reason);
    }

    @Test
    @DisplayName("沿 cause 链下钻到最内层")
    void drillsDownWholeCauseChain() throws Exception {
        Constructor<ThrowingWithCause> constructor = ThrowingWithCause.class.getDeclaredConstructor();
        ClassUtils.newInstance(constructor);

        String reason = ClassUtils.consumeLastInstantiationFailure().reason();
        assertTrue(reason.contains("内层根因-CM-88"), "应取到最内层 IOException，实际=" + reason);
        assertTrue(!reason.contains("外层包装"), "不应停在外层包装，实际=" + reason);
    }

    @Test
    @DisplayName("异常无 message 时降级为只有类型名，不能抛 NPE")
    void degradesToTypeNameWhenMessageAbsent() throws Exception {
        Constructor<ThrowingWithoutMessage> constructor = ThrowingWithoutMessage.class.getDeclaredConstructor();
        ClassUtils.newInstance(constructor);

        String reason = ClassUtils.consumeLastInstantiationFailure().reason();
        assertEquals(UnsupportedOperationException.class.getName(), reason,
                "无 message 时只返回类型名");
    }

    @Test
    @DisplayName("一次性读取：读走即清除，重复取必须是 null")
    void readIsConsuming() throws Exception {
        Constructor<ThrowingWithMessage> constructor = ThrowingWithMessage.class.getDeclaredConstructor(String.class);
        ClassUtils.newInstance(constructor, "y");

        assertNotNull(ClassUtils.consumeLastInstantiationFailure(), "第一次应取到");
        assertNull(ClassUtils.consumeLastInstantiationFailure(), "第二次必须是 null，否则会读到陈旧原因");
    }

    @Test
    @DisplayName("成功后不残留上一次失败的原因")
    void successLeavesNoStaleFailure() throws Exception {
        Constructor<ThrowingWithMessage> bad = ThrowingWithMessage.class.getDeclaredConstructor(String.class);
        ClassUtils.newInstance(bad, "z");
        assertNotNull(ClassUtils.consumeLastInstantiationFailure(), "前置条件：先制造一次失败");

        Object healthy = ClassUtils.newInstance(Healthy.class.getDeclaredConstructor(String.class), "ok");
        assertNotNull(healthy, "成功路径行为不变");
        assertNull(ClassUtils.consumeLastInstantiationFailure(),
                "一次成功必须清掉失败记录，否则调用方会拿到上一次的陈旧原因去追");
    }

    @Test
    @DisplayName("按线程隔离：别的线程的失败不会被本线程读到")
    void failureIsThreadConfined() throws Exception {
        AtomicReference<ClassUtils.NewInstanceFailure> otherThread = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                Constructor<ThrowingWithMessage> constructor =
                        ThrowingWithMessage.class.getDeclaredConstructor(String.class);
                ClassUtils.newInstance(constructor, "worker");
                otherThread.set(ClassUtils.consumeLastInstantiationFailure());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        worker.start();
        worker.join();

        assertNotNull(otherThread.get(), "工作线程自己必须能读到自己的失败");
        assertNull(ClassUtils.consumeLastInstantiationFailure(),
                "主线程不应读到工作线程的失败，否则并发下诊断会追错方向");
    }

    @Test
    @DisplayName("forObject 兜底路径同样能取回原因（构造器枚举匹配那条路）")
    void forObjectFallbackPathAlsoRecords() throws Exception {
        Object instance = ClassUtils.forObject(ThrowingWithMessage.class,
                ThrowingWithMessage.class.getClassLoader(), "fb");

        assertNull(instance, "控制流不变：失败仍返回 null");
        ClassUtils.NewInstanceFailure failure = ClassUtils.consumeLastInstantiationFailure();
        assertNotNull(failure, "forObject 兜底路径也必须留下原因");
        assertTrue(failure.reason().contains("根因标记-CM-77-fb"),
                "原因必须是构造器体的真实异常，实际=" + failure.reason());
        assertTrue(failure.typeName().contains("ThrowingWithMessage"),
                "记录里应带目标类名，实际=" + failure.typeName());
        assertTrue(failure.constructorCount() >= 1, "记录里应带构造器数量");
    }

    @Test
    @DisplayName("成功实例化后取不到任何失败记录")
    void healthyInstantiationRecordsNothing() throws Exception {
        Object instance = ClassUtils.newInstance(Healthy.class.getDeclaredConstructor(String.class), "v");

        assertEquals("v", ((Healthy) instance).value(), "成功路径返回值不变");
        assertNull(ClassUtils.consumeLastInstantiationFailure(), "成功时不应有失败记录");
    }
}