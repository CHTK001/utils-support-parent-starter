package com.chua.datasource.support.engine;

import com.chua.common.support.reflection.ReflectUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 方法处理 级别的 getter/setter 缓存，用于按字段名反射访问对象属性，避免每次调用都重新解析方法。
* <p>
* 键为 {@code (Class<?>)} + 字段名，值为 {@link MethodHandle}。
* {@link #getValue(Object, String)} 与 {@link #setValue(Object, String, Object)} 在方法缺失或调用异常时静默返回 {@code null}，不会抛出。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
final class MethodCache {

    private static final Map<Class<?>, Map<String, MethodHandle>> GETTERS = new ConcurrentHashMap<>(); // GETTERS
    private static final Map<Class<?>, Map<String, MethodHandle>> SETTERS = new ConcurrentHashMap<>(); // SETTERS

    /** 创建 方法缓存 实例 */
    private MethodCache() {
    }

    /**
    * 获取值
    *
    * @param obj obj
    * @param field 字段
    * @return 获取值的结果
     */
    static Object getValue(Object obj, String field) {
        MethodHandle mh = getter(obj.getClass(), field);
        if (mh != null) {
            try {
                return mh.invoke(obj);
            } catch (Throwable ignored) {
 // 方法处理 调用失败，降级到直接反射
            }
        }
        /* 降级：MethodHandle 跨模块受限时走 ReflectUtils */
        String camel = toCamelCase(field);
        String getterName = "get" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        Object value = ReflectUtils.invoke(obj, getterName, Object.class);
        if (value != null) {
            return value;
        }
        value = ReflectUtils.invoke(obj, field, Object.class);
        if (value != null) {
            return value;
        }
        String isGetter = "is" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        return ReflectUtils.invoke(obj, isGetter, Object.class);
    }

    /**
    * 设置值
    *
    * @param obj obj
    * @param field 字段
    * @param value 值
     */
    static void setValue(Object obj, String field, Object value) {
        MethodHandle mh = setter(obj.getClass(), field);
        if (mh != null) {
            try {
                mh.invoke(obj, value);
                return;
            } catch (Throwable ignored) {
 // 方法处理 调用失败，降级到直接反射
            }
        }
        /* 降级：MethodHandle 跨模块受限时走 ReflectUtils */
        String camel = toCamelCase(field);
        String setterName = "set" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        for (var m : obj.getClass().getMethods()) {
            if (m.getParameterCount() == 1 && m.getName().equals(setterName)) {
                ReflectUtils.invoke(obj, setterName, void.class, new Class<?>[]{m.getParameterTypes()[0]}, value);
                return;
            }
        }
    }

    /**
    * Getter
    *
    * @param clazz clazz
    * @param field 字段
    * @return getter的结果
     */
    private static MethodHandle getter(Class<?> clazz, String field) {
        Map<String, MethodHandle> classCache = GETTERS.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        return classCache.computeIfAbsent(field, k -> findGetter(clazz, field));
    }

    /**
    * Setter
    *
    * @param clazz clazz
    * @param field 字段
    * @return setter的结果
     */
    private static MethodHandle setter(Class<?> clazz, String field) {
        Map<String, MethodHandle> classCache = SETTERS.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        return classCache.computeIfAbsent(field, k -> findSetter(clazz, field));
    }

    /**
    * 查找Getter
    *
    * @param clazz clazz
    * @param field 字段
    * @return findGetter的结果
     */
    private static MethodHandle findGetter(Class<?> clazz, String field) {
        String camel = toCamelCase(field);
        MethodType mt = MethodType.methodType(Object.class, Object.class);
        String getter = "get" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        for (var m : clazz.getMethods()) {
            if (m.getParameterCount() == 0
                    && (m.getName().equals(getter) || m.getName().equals(field))) {
                MethodHandle mh = ReflectUtils.findMethodHandle(clazz, m.getName(), m.getReturnType());
                return mh != null ? mh.asType(mt) : null;
            }
        }
        String isGetter = "is" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        for (var m : clazz.getMethods()) {
            if (m.getParameterCount() == 0 && m.getName().equals(isGetter)) {
                MethodHandle mh = ReflectUtils.findMethodHandle(clazz, m.getName(), m.getReturnType());
                return mh != null ? mh.asType(mt) : null;
            }
        }
        return null;
    }

    /**
    * 查找Setter
    *
    * @param clazz clazz
    * @param field 字段
    * @return findSetter的结果
     */
    private static MethodHandle findSetter(Class<?> clazz, String field) {
        String camel = toCamelCase(field);
        String setter = "set" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        MethodType mt = MethodType.methodType(void.class, Object.class, Object.class);
        for (var m : clazz.getMethods()) {
            if (m.getParameterCount() == 1 && m.getName().equals(setter)) {
                MethodHandle mh = ReflectUtils.findMethodHandle(clazz, setter, void.class, m.getParameterTypes()[0]);
                return mh != null ? mh.asType(mt) : null;
            }
        }
        return null;
    }

    /**
    * 转为camel大小写
    *
    * @param name 名称
    * @return 转为camel大小写的结果
     */
    private static String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}