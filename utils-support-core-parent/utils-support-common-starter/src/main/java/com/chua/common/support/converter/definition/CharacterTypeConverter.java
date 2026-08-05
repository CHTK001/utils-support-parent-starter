package com.chua.common.support.converter.definition;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Character 类型转换器。
 * <p>将各种类型的值转换为 {@link Character}，取字符串的第一个字符。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/12/31
 */
public class CharacterTypeConverter implements TypeConverter<Character> {
    /**
     * 将给定值转换为 Character。
     * <p>如果是 Character 类型则直接返回，否则取 toString() 的第一个字符。</p>
     *
     * @param value 源值
     * @return Character 值，如果无法转换则返回 null
     */
    @Override
    public Character convert(Object value) {
        if (null == value) {
            return null;
        }

        if (Character.class.isAssignableFrom(value.getClass())) {
            return (Character) value;
        }
        String str = value.toString();
        if (str.length() > 0) {
            return str.charAt(0);
        }
        return null;
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Character.class
     */
    @Override
    public Class<Character> getType() {
        return Character.class;
    }
}
