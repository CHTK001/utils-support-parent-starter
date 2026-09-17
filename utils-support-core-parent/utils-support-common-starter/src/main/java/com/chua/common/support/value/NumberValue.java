package com.chua.common.support.value;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.StringUtils;

import java.util.List;


/**
 * 数字值包装实现，将字符串形式的数字解析为 {@link Number}。
 * <p>
 * 支持中文数字的大/小写格式解析（如 "壹佰贰拾叁"、"一百二十三"），
 * 通过 {@link Converter#convertIfNecessary(Object, Class)} 转换为具体数字类型。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public class NumberValue implements Value<Number> {

    /**
    * 大/中/小写层级标识（如 拾、佰、仟）
    */
    public static final List<String> HIGH_LEVEL = List.of("", "拾", "佰", "仟");
    /**
    * 小写中文数字 0-9（如 零、一、二、三）
    */
    public static final List<String> NUMBER = List.of("零", "一", "二", "三", "四", "五", "六", "七", "八", "九");
    /**
    * 数量级单位（万、亿、万亿）
    */
    public static final List<String> LEVEL = List.of("", "万", "亿", "万亿");

    /**
    * 原始字符串值
    */
    private final String source;

    /**
    * 构造函数，传入需要解析的数字字符串。
    *
    * @param source 数字字符串（支持中文数字和阿拉伯数字）
    */
    public NumberValue(String source) {
        this.source = source;
    }

    /**
    * 判断值是否为 空 或无效数字。
    *
    * @return true 表示值为空或无法转换为数字
    */
    @Override
    public boolean isNull() {
 // 空字符串或无法转换为 数字 时视为 空
        return StringUtils.isEmpty(source) || null == Converter.convertIfNecessary(source, Number.class);
    }

    /**
    * 判断当前值是否等于指定数字。
    *
    * @param value 指定数字
    * @return true 表示相等
    */
    @Override
    public boolean is(Number value) {
        // 比较数值是否相等
        return null != value && value.equals(getValue());
    }

    /**
    * 获取数字值。
    *
    * @return 转换后的 数字 值，如果无法转换则返回 空
    */
    @Override
    public Number getValue() {
 // 将源字符串转换为 数字 类型
        Number number = Converter.convertIfNecessary(source, Number.class);
        if (null != number) {
            return number;
        }
        return null;
    }

    /**
    * 获取转换异常。
    *
    * @return 始终返回 空（当前实现不记录异常）
    */
    @Override
    public Throwable getThrowable() {
        // 当前实现不记录异常
        return null;
    }
}
