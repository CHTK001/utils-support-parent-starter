package com.chua.common.support.vector;

/**
 * 运行时环境检测 SPI。
 *
 * <p>各向量存储后端实现此接口，声明自身运行所需的硬件/库条件。
 * {@link VectorStorageProvider} 自动收集所有实现，按优先级选出最优后端。</p>
 *
 * <pre>{@code
 * // cuVS 后端
 * @Spi(value = "cuvs", order = 100)
 * @SpiCondition(onCondition = CuvsRuntimeCondition.class)
 * public class CuvsRuntimeDetector implements RuntimeDetector {
 *     @Override public String name() { return "cuvs"; }
 *     @Override public boolean isAvailable() { ... }
 *     @Override public int priority() { return 100; }
 * }
 *
 * // jvector 后端（始终可用）
 * public class JvectorRuntimeDetector implements RuntimeDetector {
 *     @Override public String name() { return "jvector"; }
 *     @Override public boolean isAvailable() { return true; }
 *     @Override public int priority() { return 50; }
 * }
 * }</pre>de 公共 int priority() { 返回 50; }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see VectorStorageProvider
 */
public interface RuntimeDetector {

    /**
     * 后端名称，与 SPI 名称保持一致。
     *
     * @return 后端名称
     */
    String name();

    /**
     * 检测当前运行环境是否满足该后端的要求。
     *
     * <p>示例：cuVS 需要 CUDA Driver + libcuvs native 库；
     * jvector 纯 Java 实现，始终返回 true。</p>
     *
     * @return true 表示当前环境支持该后端
     */
    boolean isAvailable();

    /**
     * 优先级，值越大越优先被选中。
     *
     * <p>用于多个后端同时可用时选择最优方案，例如 cuVS(100) > jvector(50) > memory(-100)。</p>
     *
     * @return 优先级值
     */
    default int priority() {
        return 0;
    }
}
