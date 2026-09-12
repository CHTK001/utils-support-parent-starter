package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ArrayUtils;

import java.util.List;

import static com.chua.common.support.constant.CommonConstant.*;


/**
* Integer[] 类型转换器。
* <p>将各种类型的值转换为 {@code Integer[]}，支持以下输入类型：</p>
* <ul>
*   <li>数组 / 集合 — 通过 {@link #transToArray(Object, Class)} 转换</li>
*   <li>{@link String} — 支持 JSON 数组格式和多分隔符（逗号/分号/空格/制表符/换行）的字符串</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/11/5
 */
public class IntegerArrayTypeConverter implements TypeConverter<Integer[]> {

    /**
    * 将给定值转换为 Integer[]。
    *
    * @param value 源值
    * @return Integer[] 值，如果为 null 则返回空数组
     */
    @Override
    public Integer[] convert(Object value) {
        if (null == value) {
            return new Integer[0];
        }

        Integer[] valueArray = transToArray(value, Integer.class);
        if (valueArray.length != 0) {
            return valueArray;
        }

        if (value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                    stringValue = stringValue.substring(1, stringValue.length() - 1);
                }
            //             
            String[] parts = stringValue.split("[\\n\\r\\t ,;|]+");
            return transToArray(parts, Integer.class);
        }

        return new Integer[0];
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Integer[].class
     */
    @Override
    public Class<Integer[]> getType() {
        return Integer[].class;
    }

}
