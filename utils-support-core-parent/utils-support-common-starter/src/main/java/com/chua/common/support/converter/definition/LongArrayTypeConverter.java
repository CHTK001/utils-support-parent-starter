package com.chua.common.support.converter.definition;

import java.util.List;

import static com.chua.common.support.constant.CommonConstant.*;


/**
 * Long[] 类型转换器。
 * <p>将各种类型的值转换为 {@code Long[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>数组 / 集合 — 通过 {@link #transToArray(Object, Class)} 转换</li>
 *   <li>{@link String} — 支持 JSON 数组格式和逗号分隔的字符串</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
public class LongArrayTypeConverter implements TypeConverter<Long[]> {

    /**
     * 将给定值转换为 Long[]。
     *
     * @param value 源值
     * @return Long[] 值，如果为 null 则返回空数组
     */
    @Override
    public Long[] convert(Object value) {
        if (null == value) {
            return new Long[0];
        }

        Long[] valueArray = transToArray(value, Long.class);
        if (valueArray.length != 0) {
            return valueArray;
        }

        if (value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                stringValue = stringValue.substring(1, stringValue.length() - 1);
            }
            return transToArray(stringValue.split(SYMBOL_COMMA), Long.class);
        }

        return new Long[0];
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Long[].class
     */
    @Override
    public Class<Long[]> getType() {
        return Long[].class;
    }

}
