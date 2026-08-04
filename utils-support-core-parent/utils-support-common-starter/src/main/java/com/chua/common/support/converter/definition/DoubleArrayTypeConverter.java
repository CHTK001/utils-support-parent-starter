package com.chua.common.support.converter.definition;


import static com.chua.common.support.constant.CommonConstant.*;
import org.jspecify.annotations.NullUnmarked;


/**
 * Double[] 类型转换器。
 * <p>将各种类型的值转换为 {@code Double[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>数组 / 集合 — 通过 {@link #transToArray(Object, Class)} 转换</li>
 *   <li>{@link String} — 支持 JSON 数组格式（[1.1,2.2]）和逗号分隔的字符串</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
@NullUnmarked
public class DoubleArrayTypeConverter implements TypeConverter<Double[]> {


    /**
     * 将给定值转换为 Double[]。
     *
     * @param value 源值
     * @return Double[] 值，如果为 null 则返回空数组
     */
    @Override
    public Double[] convert(Object value) {
        if (null == value) {
            return new Double[0];
        }

        Double[] valueArray = transToArray(value, Double.class);
        if (valueArray.length != 0) {
            return valueArray;
        }

        if (value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                stringValue = stringValue.substring(1, stringValue.length() - 1);
            }
            return transToArray(stringValue.split(SYMBOL_COMMA), Double.class);
        }

        return new Double[0];
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Double[].class
     */
    @Override
    public Class<Double[]> getType() {
        return Double[].class;
    }

}
