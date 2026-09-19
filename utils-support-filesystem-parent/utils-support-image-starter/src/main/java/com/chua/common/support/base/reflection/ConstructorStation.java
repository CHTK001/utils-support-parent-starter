package com.chua.common.support.base.reflection;

import com.chua.common.support.reflection.ReflectUtils;

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
        return newInstance(constructor, new Object[0]);
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
        if (constructor == null) {
            throw new IllegalArgumentException("constructor 不能为 null");
        }
        T instance = ReflectUtils.instantiate(constructor.getDeclaringClass(), args);
        if (instance == null) {
            throw new RuntimeException("Failed to create instance via constructor");
        }
        return instance;
    }
}
