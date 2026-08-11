package com.chua.common.support.reflection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 字段访问工具，通过反射按字段名读写对象属性。
 *
 * @author CH
 * @since 2024/8/6
 */
public final class FieldStation {

    private static final Logger log = LoggerFactory.getLogger(FieldStation.class);

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
        } catch (NoSuchFieldException e) {
            // 字段不存在属于业务正常情况（sys_setting_name 与 Bean 字段未对齐），不记录 ERROR
            log.debug("[FieldStation] 字段不存在: type={}, name={}", type.getName(), name);
            return null;
        } catch (IllegalAccessException e) {
            log.warn("[FieldStation] 字段访问被拒绝: type={}, name={}", type.getName(), name, e);
            return null;
        } catch (Exception e) {
            log.error("[FieldStation] 读取字段失败: type={}, name={}", type.getName(), name, e);
            return null;
        }
    }

    public void setIgnoreNameValue(String name, Object value) {
        try {
            Field field = findField(toCamelCase(name));
            field.setAccessible(true);
            field.set(instance, value);
        } catch (NoSuchFieldException e) {
            log.debug("[FieldStation] 字段不存在，跳过写入: type={}, name={}", type.getName(), name);
        } catch (IllegalAccessException e) {
            log.warn("[FieldStation] 字段写入被拒绝: type={}, name={}", type.getName(), name, e);
        } catch (Exception e) {
            log.error("[FieldStation] 写入字段失败: type={}, name={}", type.getName(), name, e);
        }
    }

    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        char first = name.charAt(0);
        return Character.isUpperCase(first) ? (Character.toLowerCase(first) + name.substring(1)) : name;
    }

    private Field findField(String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("No such field: " + name + " in " + type.getName());
    }
}
