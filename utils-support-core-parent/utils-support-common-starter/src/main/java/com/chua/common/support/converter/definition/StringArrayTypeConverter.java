package com.chua.common.support.converter.definition;

import com.chua.common.support.constant.CommonConstant;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static com.chua.common.support.constant.CommonConstant.*;
import org.jspecify.annotations.NullUnmarked;


/**
 * String[] 类型转换器。
 * <p>将各种类型的值转换为 {@code String[]}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@code String[]} — 直接返回</li>
 *   <li>{@link String} — 支持 JSON 数组格式（[a,b,c]）和大括号对象组格式（{a}{b}{c}）</li>
 *   <li>{@link URL} / {@code URL[]} — 转为外部格式字符串</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/5
 */
@NullUnmarked
public class StringArrayTypeConverter implements TypeConverter<String[]> {

    final public static Pattern PATTERN = Pattern.compile("\\}[\\s]{0,},[\\s]{0,}\\{");

    /**
     * 将给定值转换为 String[]。
     *
     * @param value 源值
     * @return String[] 值，如果为 null 则返回空数组
     */
    @Override
    public String[] convert(Object value) {
        if (null == value) {
            return new String[0];
        }

        if (value instanceof String[]) {
            return (String[]) value;
        }

        if (value instanceof String) {
            String stringValue = value.toString();
            if (stringValue.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && stringValue.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                String inner = stringValue.substring(1, stringValue.length() - 1);
                String[] parts = inner.split(SYMBOL_COMMA);
                return transToArray(parts, String.class);
            } else if (stringValue.startsWith(SYMBOL_LEFT_BIG_PARENTHESES) && stringValue.endsWith(SYMBOL_RIGHT_BIG_PARENTHESES)) {
                String[] split = PATTERN.split(stringValue);
                List<String> result = new ArrayList<>(split.length);
                for (String it : split) {
                    if (!it.startsWith(SYMBOL_LEFT_BIG_PARENTHESES)) {
                        it = SYMBOL_LEFT_BIG_PARENTHESES + it;
                    }
                    if (!it.endsWith(CommonConstant.SYMBOL_RIGHT_BIG_PARENTHESES)) {
                        it += CommonConstant.SYMBOL_RIGHT_BIG_PARENTHESES;
                    }
                    result.add(it);
                }

                return result.toArray(new String[0]);
            }

            if (value instanceof URL) {
                return new String[]{((URL) value).toExternalForm()};
            }

            if (value instanceof URL[]) {
                return Arrays.stream(((URL[]) value)).map(java.net.URL::toExternalForm).toArray(String[]::new);
            }

            return stringValue.split(SYMBOL_COMMA);
        }

        return new String[0];
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return String[].class
     */
    @Override
    public Class<String[]> getType() {
        return String[].class;
    }
}
