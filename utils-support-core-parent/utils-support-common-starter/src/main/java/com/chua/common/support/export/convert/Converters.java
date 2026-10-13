package com.chua.common.support.export.convert;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.spi.ValueConverter;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 值转换器注册表。
 *
 * <p>查找顺序：</p>
 * <ol>
 *     <li>显式 {@code Converters.register(name, converter)} 注册的实例</li>
 *     <li>{@code ValueConverter} SPI 中别名匹配的实现</li>
 *     <li>全限定类名反射实例化</li>
 *     <li>内置转换器（日期 / 枚举 / JSON / 前后缀 / 空值）</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Converters {

    /**
     * 显式注册的转换器：名称 → 实例。
     */
    private static final Map<String, ValueConverter> REGISTRY = new ConcurrentHashMap<>();

    /**
     * 反射实例缓存：类 → 实例。
     */
    private static final Map<Class<?>, ValueConverter> INSTANCES = new ConcurrentHashMap<>();

    private Converters() {
    }

    /**
     * 注册转换器实例。
     *
     * @param name      名称
     * @param converter 转换器
     */
    public static void register(String name, ValueConverter converter) {
        if (name != null && !name.isBlank() && converter != null) {
            REGISTRY.put(name, converter);
        }
    }

    /**
     * 查找转换器，未找到返回 {@code null}。
     *
     * @param key 转换器标识（SPI 别名或全限定类名）
     * @return 转换器
     */
    public static ValueConverter find(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        ValueConverter registered = REGISTRY.get(key);
        if (registered != null) {
            return registered;
        }
        ValueConverter spi = ServiceProvider.of(ValueConverter.class).getExtension(key);
        if (spi != null) {
            return spi;
        }
        return newInstance(key);
    }

    /**
     * 按类名反射实例化转换器。
     *
     * @param className 全限定类名
     * @return 转换器，失败返回 {@code null}
     */
    private static ValueConverter newInstance(String className) {
        Class<?> type = ReflectUtils.forName(className);
        if (type == null || !ValueConverter.class.isAssignableFrom(type)) {
            return null;
        }
        return INSTANCES.computeIfAbsent(type, it -> {
            try {
                return (ValueConverter) it.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("转换器实例化失败: " + it.getName(), e);
            }
        });
    }

    /**
     * 清空显式注册表（仅测试使用）。
     */
    public static void clear() {
        REGISTRY.clear();
        INSTANCES.clear();
    }

    /**
     * 获取可用转换器名称列表。
     *
     * @return 名称列表
     */
    public static java.util.Set<String> names() {
        java.util.Set<String> names = Wraps.set();
        names.addAll(REGISTRY.keySet());
        names.addAll(ServiceProvider.of(ValueConverter.class).getExtensions());
        return names;
    }

    /**
     * 判断列是否声明了自定义转换器。
     *
     * @param column 列元数据
     * @return 是否声明
     */
    static boolean hasCustom(ColumnMeta column) {
        return !column.getConverter().isEmpty();
    }
}