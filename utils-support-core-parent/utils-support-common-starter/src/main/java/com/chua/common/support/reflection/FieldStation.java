package com.chua.common.support.reflection;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 字段访问工具。
 * <p>
 * 通过 {@link ReflectUtils} 提供的字段读写、查找能力封装，
   * 提供对 Bean 字段按字符串名称的动态读写能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FieldStation {

    /** 实例 */
    private final Object instance;

    /** 类型 */
    private final Class<?> type;

    /**
     * 私有构造。
     *
     * @param instance 实例
     * @param type     类型
     */
    private FieldStation(Object instance, Class<?> type) {
        this.instance = instance;
        this.type = type;
    }

    /**
      * 按类创建 字段状态。
     *
     * @param type 类型
     * @return FieldStation 实例
     */
    public static FieldStation of(Class<?> type) {
        return new FieldStation(null, type);
    }

    /**
      * 按实例创建 字段状态。
     *
     * @param instance 实例，允许为 空
     * @return FieldStation 实例
     */
    public static FieldStation of(Object instance) {
        if (instance == null) {
            return new FieldStation(null, null);
        }
        return new FieldStation(instance, instance.getClass());
    }

    /**
     * 读取字段值。
     *
     * @param name 字段名（支持 pascal大小写，自动转 camel大小写）
     * @return 字段值，不存在返回 空
     */
    public Object getValue(String name) {
        if (type == null) {
            return null;
        }
        return ReflectUtils.getField(instance, toCamelCase(name));
    }

    /**
     * 按名写入字段值。
     *
     * @param name  字段名
     * @param value 值
     */
    public void setIgnoreNameValue(String name, Object value) {
        if (type == null) {
            return;
        }
        ReflectUtils.setField(instance, toCamelCase(name), value);
    }

    /**
      * 将字段名首字母大写转小写（pascal大小写 → camel大小写）。
     *
     * @param name 字段名
     * @return 转换后的字段名
     */
    private String toCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first)) {
            return name;
        }
        return Character.toLowerCase(first) + name.substring(1);
    }

    /**
     * 查找字段（含继承链），结果缓存。
     *
     * @param type 目标类型
     * @param name 字段名
     * @return Field 对象
     * @throws NoSuchFieldException 当字段不存在时
     */
    static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = ReflectUtils.findField(type, name);
        if (field == null) {
            throw new NoSuchFieldException("No such field: " + name + " in " + type.getName());
        }
        return field;
    }

    /**
     * 测试钩子：暴露包级访问以便单元测试验证缓存命中行为。
     *
     * @param type 目标类型
     * @param name 字段名
     * @return Field 对象
     */
    static Field findFieldForTest(Class<?> type, String name) {
        try {
            return findField(type, name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("测试期望字段存在: " + name, e);
        }
    }
}