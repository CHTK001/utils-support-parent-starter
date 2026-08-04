package com.chua.common.support.converter.definition;

import com.chua.common.support.converter.Converter;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * 枚举类型转换器。
 * <p>将各种类型的值转换为 {@link Enum}，支持按枚举名称或序号匹配。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/12/19
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class EnumTypeConverter implements TypeConverter<Enum> {

    /**
     * 将给定值转换为 Enum。
     * <p>如果值已是 Enum 则直接返回，否则兜底调用 {@link #convertIfNecessary(Object)}。</p>
     *
     * @param value 源值
     * @return Enum 值，如果无法转换则返回 null
     */
    @Override
    public Enum convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value instanceof Enum) {
            return (Enum) value;
        }

        return convertIfNecessary(value);
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Enum.class
     */
    @Override
    public Class<Enum> getType() {
        return Enum.class;
    }

    /**
     * 将值转换为指定枚举类型。
     * <p>支持按序号（Number 类型）或名称字符串匹配枚举常量。</p>
     *
     * @param value   源值
     * @param newType 目标枚举类型
     * @param <T>     枚举泛型类型
     * @return 枚举常量，如果无法匹配则返回 null
     */
    public <T> T convertFor(Object value, Class<T> newType) {
        if (null == value) {
            return null;
        }

        T[] enumConstants = newType.getEnumConstants();
        for (T enumConstant : enumConstants) {
            if (value instanceof Number) {
                int intValue = ((Number) value).intValue();
                Enum e = (Enum) enumConstant;
                if (e.ordinal() == intValue) {
                    return enumConstant;
                }

                continue;
            }
            if (enumConstant.toString().equalsIgnoreCase(Converter.convertIfNecessary(value, String.class))) {
                return enumConstant;
            }
        }
        return null;
    }

}
