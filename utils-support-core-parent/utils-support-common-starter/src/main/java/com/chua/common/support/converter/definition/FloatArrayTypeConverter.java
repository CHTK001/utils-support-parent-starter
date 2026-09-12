package com.chua.common.support.converter.definition;

import static com.chua.common.support.constant.CommonConstant.*;


/**
* Float[] 类型转换器。
* <p>将各种类型的值转换为 {@code Float[]}，支持以下输入类型：</p>
* <ul>
*   <li>数组 / 集合 — 通过 {@link #transToArray(Object, Class)} 转换</li>
*   <li>{@link String} — 支持 JSON 数组格式和逗号分隔的字符串</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/11/5
 */
public class FloatArrayTypeConverter implements TypeConverter<Float[]> {


    /**
    * 将给定值转换为 Float[]。
    *
    * @param value 源值
    * @return Float[] 值，如果为 null 则返回空数组
     */
    @Override
    public Float[] convert(Object value) {
        if (null == value) {
            return new Float[0];
        }

        Float[] valueArray = transToArray(value, Float.class);
        if (valueArray.length != 0) {
            return valueArray;
        }

        if (value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                stringValue = stringValue.substring(1, stringValue.length() - 1);
            }
            return transToArray(stringValue.split(SYMBOL_COMMA), Float.class);
        }

        return new Float[0];
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Float[].class
     */
    @Override
    public Class<Float[]> getType() {
        return Float[].class;
    }

}
