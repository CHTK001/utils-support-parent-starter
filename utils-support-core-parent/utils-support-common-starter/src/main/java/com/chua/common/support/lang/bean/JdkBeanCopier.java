package com.chua.common.support.lang.bean;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDK 反射实现的 Bean 属性拷贝器。
 *
 * <p>通过 {@link PropertyDescriptor} 结合 {@link java.beans.Introspector} 获取属性信息，
 * 使用反射调用 getter/setter 方法完成属性复制。内部缓存类的属性描述信息以提升性能。</p>
 *
 * <p>支持以下特性：</p>
 * <ul>
 *   <li>基本类型与包装类型的自动转换</li>
 *   <li>String 到基本类型的转换</li>
 *   <li>忽略指定属性</li>
 *   <li>Map 与 JavaBean 之间的互相转换</li>
 *   <li>属性描述信息缓存</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
@SpiDefault
@Spi("jdk")
@SuppressWarnings("ALL")
public class JdkBeanCopier implements BeanCopier {

    /**
     * 属性描述信息缓存（类 -> 属性名 -> PropertyDescriptor）
     */
    private static final Map<Class<?>, Map<String, PropertyDescriptor>> READ_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, PropertyDescriptor>> WRITE_CACHE = new ConcurrentHashMap<>();

    @Override
    /** 复制Properties */
    public void copyProperties(Object source, Object target) {
        copyProperties(source, target, (String[]) null);
    }

    @Override
    /** 复制Properties */
    public void copyProperties(Object source, Object target, String... ignoreProperties) {
        if (source == null || target == null) {
            return;
        }

        Set<String> ignoreSet = ignoreProperties != null
                ? new HashSet<>(Arrays.asList(ignoreProperties))
                : Collections.emptySet();

        Class<?> sourceClass = source.getClass();
        Class<?> targetClass = target.getClass();

        Map<String, PropertyDescriptor> sourceReads = getReadDescriptors(sourceClass);
        Map<String, PropertyDescriptor> targetWrites = getWriteDescriptors(targetClass);

        for (Map.Entry<String, PropertyDescriptor> entry : sourceReads.entrySet()) {
            String propName = entry.getKey();
            if (ignoreSet.contains(propName)) {
                continue;
            }

            PropertyDescriptor targetPd = targetWrites.get(propName);
            if (targetPd == null) {
                continue;
            }

            try {
                Method readMethod = entry.getValue().getReadMethod();
                Method writeMethod = targetPd.getWriteMethod();
                if (readMethod == null || writeMethod == null) {
                    continue;
                }

                Object value = ReflectUtils.invoke(source, readMethod.getName(), readMethod.getReturnType());
                if (value != null) {
                    ReflectUtils.invoke(target, writeMethod.getName(), void.class, writeMethod.getParameterTypes(), convertIfNeeded(value, writeMethod.getParameterTypes()[0]));
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** 复制Properties */
    public void copyProperties(Map<String, Object> sourceMap, Object target) {
        if (sourceMap == null || target == null) {
            return;
        }

        Class<?> targetClass = target.getClass();
        Map<String, PropertyDescriptor> targetWrites = getWriteDescriptors(targetClass);

        for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
            PropertyDescriptor targetPd = targetWrites.get(entry.getKey());
            if (targetPd == null) {
                continue;
            }

            try {
                Method writeMethod = targetPd.getWriteMethod();
                if (writeMethod == null) {
                    continue;
                }
                Object value = entry.getValue();
                if (value != null) {
                    ReflectUtils.invoke(target, writeMethod.getName(), void.class, writeMethod.getParameterTypes(), convertIfNeeded(value, writeMethod.getParameterTypes()[0]));
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    /** 复制Properties */
    public void copyProperties(Object source, Map<String, Object> target) {
        if (source == null || target == null) {
            return;
        }

        Class<?> sourceClass = source.getClass();
        Map<String, PropertyDescriptor> sourceReads = getReadDescriptors(sourceClass);

        for (Map.Entry<String, PropertyDescriptor> entry : sourceReads.entrySet()) {
            try {
                Method readMethod = entry.getValue().getReadMethod();
                if (readMethod == null) {
                    continue;
                }
                Object value = ReflectUtils.invoke(source, readMethod.getName(), readMethod.getReturnType());
                target.put(entry.getKey(), value);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取读取Descriptors
     * @param clazz 类，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, PropertyDescriptor> getReadDescriptors(Class<?> clazz) {
        return READ_CACHE.computeIfAbsent(clazz, JdkBeanCopier::resolveReadDescriptors);
    }

    /**
     * 获取写入Descriptors
     * @param clazz 类，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, PropertyDescriptor> getWriteDescriptors(Class<?> clazz) {
        return WRITE_CACHE.computeIfAbsent(clazz, JdkBeanCopier::resolveWriteDescriptors);
    }

    /**
     * 解析读取Descriptors
     * @param clazz 类，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, PropertyDescriptor> resolveReadDescriptors(Class<?> clazz) {
        Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
        for (PropertyDescriptor pd : getPropertyDescriptors(clazz)) {
            if (pd.getReadMethod() != null) {
                result.put(pd.getName(), pd);
            }
        }
        return result;
    }

    /**
     * 解析写入Descriptors
     * @param clazz 类，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private static Map<String, PropertyDescriptor> resolveWriteDescriptors(Class<?> clazz) {
        Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
        for (PropertyDescriptor pd : getPropertyDescriptors(clazz)) {
            if (pd.getWriteMethod() != null) {
                result.put(pd.getName(), pd);
            }
        }
        return result;
    }

    /**
     * 获取PropertyDescriptors
     * @param clazz 类，不允许为 null
     * @return PropertyDescriptor 对象
     */
    private static PropertyDescriptor[] getPropertyDescriptors(Class<?> clazz) {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(clazz, Object.class);
            return beanInfo.getPropertyDescriptors();
        } catch (Exception e) {
            return new PropertyDescriptor[0];
        }
    }

    /**
     * 转换IfNeeded
     * @param value 值，不允许为 null
     * @param targetType 目标类型，不允许为 null
     * @return 对象 对象
     */
    private static Object convertIfNeeded(Object value, Class<?> targetType) {
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (targetType == long.class || targetType == Long.class) {
            return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
        }
        if (targetType == double.class || targetType == Double.class) {
            return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return value;
    }
}
