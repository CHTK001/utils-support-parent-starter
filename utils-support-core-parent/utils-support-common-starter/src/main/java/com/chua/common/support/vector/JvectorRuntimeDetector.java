package com.chua.common.support.vector;

/**
 * jvector CPU 环境检测器。
 *
 * <p>jvector 是纯 Java 实现，无需任何 native 依赖，始终可用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RuntimeDetector
 */
public class JvectorRuntimeDetector implements RuntimeDetector {

    @Override
    public String name() {
        return "jvector";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 50;
    }
}
