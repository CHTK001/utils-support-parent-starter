package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_COMMA;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SQUARE_BRACKET;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_RIGHT_SQUARE_BRACKET;


/**
 * List 类型转换器。
 * <p>将各种类型的值转换为 {@link List}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link List} — 直接返回</li>
 *   <li>{@link java.util.Collection} — 转为 ArrayList</li>
 *   <li>{@link Iterable} — 遍历元素构造 LinkedList</li>
 *   <li>{@link String} — 支持 JSON 数组格式（[a,b,c]）和多分隔符字符串（逗号/分号/空格/制表符/换行分隔）</li>
 *   <li>数组类型 — 通过 ArrayUtils.toList 转换</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
public class ListTypeConverter implements TypeConverter<List> {


    /**
     * 单例实例
    */
    public static final ListTypeConverter INSTANCE = new ListTypeConverter();

    /**
     * 将给定值转换为 List。
     *
     * @param value 源值
     * @return List 值，如果为 null 则返回 null
     */
    @Override
    public List convert(Object value) {
        if (null == value) {
            return null;
        }

        if (isAssignableFrom(value, List.class)) {
            return (List) value;
        }

        if (isAssignableFrom(value, Collection.class)) {
            return new ArrayList((Collection) value);
        }

        if(value instanceof Iterable) {
            List<Object> tpl = new LinkedList<>();
            ((Iterable<?>) value).forEach(tpl::add);
            return tpl;
        }

        if (isAssignableFrom(value, String.class)) {
            String string = value.toString().trim();
            if (string.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && string.endsWith(SYMBOL_RIGHT_SQUARE_BRACKET)) {
                boolean isListMap = string.startsWith("[{");
                String substring = string.substring(1, string.length() - 1);
                if (isListMap) {
                    String[] split = substring.split("\\},\\{");
                    List<Map<String, Object>> result = new LinkedList<>();
                    for (String s : split) {
                        result.add(new MapTypeConverter().convert(s));
                    }
                    return result;
                } else {
                    return Arrays.asList(substring.split(SYMBOL_COMMA));
                }
            }

            String[] parts = string.split("[\\n\\r\\t ,;|]+");
            if (parts.length > 1 || (parts.length == 1 && parts[0].length() > 0)) {
                return new ArrayList<>(Arrays.asList(parts));
            }
        }

        if (value.getClass().isArray()) {
            return (List) ArrayUtils.toList(value);
        }

        return convertIfNecessary(value);
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return List.class
     */
    @Override
    public Class<List> getType() {
        return List.class;
    }
}
