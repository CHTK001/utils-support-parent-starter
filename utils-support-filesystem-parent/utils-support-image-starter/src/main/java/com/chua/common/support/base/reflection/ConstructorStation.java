package com.chua.common.support.base.reflection;

import java.lang.reflect.Constructor;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class ConstructorStation {

    @SuppressWarnings("unchecked")
    /**
     * 新instance
     *
     * @param constructor constructor
     * @return 新instance的结果
     */
    public static <T> T newInstance(Constructor<T> constructor) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance via constructor", e);
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * 新instance
     *
     * @param constructor constructor
     * @param args 参数
     * @return 新instance的结果
     */
    public static <T> T newInstance(Constructor<T> constructor, Object... args) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance via constructor", e);
        }
    }
}
