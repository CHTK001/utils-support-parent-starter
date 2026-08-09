package com.chua.common.support.reflection;

import java.lang.reflect.Field;

/**
 * 字段访问工具，通过反射按字段名读写对象属性。
 *
 * @author CH
 * @since 2024/8/6
 */
public final class FieldStation {

    private final Object instance;
    private final Class<?> type;

    private FieldStation(Object instance, Class<?> type) {
        this.instance = instance;
        this.type = type;
    }

    public static FieldStation of(Class<?> type) {
        return new FieldStation(null, type);
    }

    public static FieldStation of(Object instance) {
        return new FieldStation(instance, instance.getClass());
    }

    public Object getValue(String name) {
        try {
            Field field = findField(toCamelCase(name));
            field.setAccessible(true);
            return field.get(instance);
        } catch (Exception e) {
            return null;
        }
    }

    public void setIgnoreNameValue(String name, Object value) {
        try {
            Field field = findField(toCamelCase(name));
            field.setAccessible(true);
            field.set(instance, value);
        } catch (Exception ignored) {
        }
    }

    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        char first = name.charAt(0);
        return Character.isUpperCase(first) ? (Character.toLowerCase(first) + name.substring(1)) : name;
    }

    private Field findField(String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalArgumentException("No such field: " + name + " in " + type.getName());
    }
}
