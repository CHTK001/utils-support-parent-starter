package com.chua.common.support.converter.definition;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.ArrayUtils;
import com.chua.common.support.utils.ClassUtils;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_COMMA;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SQUARE_BRACKET;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_RIGHT_SQUARE_BRACKET;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Object[] 类型转换器。
 * <p>将各种类型的值转换为 {@code Object[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>各种包装类型数组（String[]、Integer[]、Boolean[] 等）— 直接返回</li>
 *   <li>{@link String} — 支持 JSON 数组格式和逗号分隔字符串</li>
 *   <li>{@link java.util.Collection} — 通过 toArray() 转换</li>
 *   <li>其他类型 — 包装为单元素数组</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
public class ObjectArrayTypeConverter implements TypeConverter<Object[]> {

    /**
     * 将给定值转换为 Object[]。
     *
     * @param value 源值
     * @return Object[] 值，如果为 null 则返回空数组
     */
    @Override
    public Object[] convert(Object value) {
        if (null == value) {
            return new Object[0];
        }

        if (value instanceof String[]) {
            return (String[]) value;
        }
        if (value instanceof Integer[]) {
            return (Integer[]) value;
        }
        if (value instanceof Boolean[]) {
            return (Boolean[]) value;
        }
        if (value instanceof Short[]) {
            return (Short[]) value;
        }
        if (value instanceof Long[]) {
            return (Long[]) value;
        }
        if (value instanceof Float[]) {
            return (Float[]) value;
        }
        if (value instanceof Byte[]) {
            return (Byte[]) value;
        }
        if (value instanceof Double[]) {
            return (Double[]) value;
        }

        if (value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                stringValue = stringValue.substring(1, stringValue.length() - 1);
            }

            return stringValue.split(SYMBOL_COMMA);
        }

        if (value instanceof Collection) {
            return ((Collection<?>) value).toArray();
        }

        return new Object[]{value};
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Object[].class
     */
    @Override
    public Class<Object[]> getType() {
        return Object[].class;
    }

    /**
     * 将值转换为指定元素类型的目标数组（支持泛型）。
     * <p>从 Collection / Map（取 values）/ 数组中逐个元素进行类型转换后再组装为目标数组。</p>
     *
     * @param value   源值
     * @param newType 目标数组的元素类型
     * @param <T>     目标元素泛型类型
     * @return 转换后的目标类型数组
     */
    @SuppressWarnings("ALL")
    public <T> T convertFor(Object value, Class<T> newType) {
        if (null == value) {
            return (T) Array.newInstance(newType, 0);
        }

        List tpl = new LinkedList();
        Class<?> actualType = ClassUtils.getActualType(newType);
        if (value instanceof Collection) {
            ((Collection<?>) value).forEach(it -> {
                if(ClassUtils.isPrimitive(actualType)) {
                    tpl.add(Converter.convertIfNecessary(it, actualType));
                    return;
                }
                tpl.add(Converter.convertIfNecessary(it, actualType));
            });

            return (T) ArrayUtils.toArray(tpl);
        }

        if (value instanceof Map) {
            ((Map) value).values().forEach(it -> {
                tpl.add(Converter.convertIfNecessary(it, actualType));
            });

            return (T) ArrayUtils.toArray(tpl);
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = Array.get(value, i);
                tpl.add(Converter.convertIfNecessary(o, actualType));
            }

            return (T) ArrayUtils.toArray(tpl);
        }

        Object[] ts = new Object[]{Converter.convertIfNecessary(value, actualType)};
        Object newInstance = Array.newInstance(actualType, 1);
        System.arraycopy(ts, 0, newInstance, 0, 1);
        return (T) newInstance;
    }
}
