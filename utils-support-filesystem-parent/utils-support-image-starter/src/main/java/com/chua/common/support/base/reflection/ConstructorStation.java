package com.chua.common.support.base.reflection;

import java.lang.reflect.Constructor;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class ConstructorStation {

    @SuppressWarnings("unchecked")
    /** NewInstance */
    public static <T> T newInstance(Constructor<T> constructor) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance via constructor", e);
        }
    }

    @SuppressWarnings("unchecked")
    /** NewInstance */
    public static <T> T newInstance(Constructor<T> constructor, Object... args) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance via constructor", e);
        }
    }
}
