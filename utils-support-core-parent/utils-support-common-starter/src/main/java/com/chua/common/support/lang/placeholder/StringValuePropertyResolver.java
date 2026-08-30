package com.chua.common.support.lang.placeholder;

import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * 字符串值属性解析器，用于解析包含占位符的字符串。
 * 支持嵌套占位符、默认值设置以及循环引用检测。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class StringValuePropertyResolver implements PropertyResolver {

    /** Placeholdersupport */
    private final PlaceholderSupport placeholderSupport;

    /**
     * 定义右括号到左括号的映射关系，用于处理简单的嵌套前缀匹配
     */
    private static final Map<String, String> STRING_STRING_HASH_MAP = new HashMap<>(4);

    static {
        STRING_STRING_HASH_MAP.put("}", "{");
        STRING_STRING_HASH_MAP.put("]", "[");
        STRING_STRING_HASH_MAP.put(")", "(");
    }

    /**
     * 简化的前缀，用于检测嵌套占位符（例如：${...} 中的 ${）
     */
    private final String simplePrefix;
    /**
     * 实际的占位符解析器实例
     */
    private final PlaceholderResolver placeholderResolver;
    /**
     * 用于检测循环引用的集合，记录当前正在解析的占位符键
     */
    private Set<String> visitedPlaceholders;

    /**
     * 创建 StringValuePropertyResolver 实例
     * @param placeholderSupport placeholderSupport
     */
    public StringValuePropertyResolver(PlaceholderSupport placeholderSupport) {
        this.placeholderSupport = placeholderSupport;
        // 根据配置的占位符前缀，查找对应的简化前缀（如从 "}" 获取 "{"）
        String simplePrefixForSuffix = STRING_STRING_HASH_MAP.get(placeholderSupport.getPlaceholderPrefix());
        // 如果存在映射且前缀以该字符结尾，则使用简化前缀；否则直接使用原始前缀
        if (simplePrefixForSuffix != null && placeholderSupport.getPlaceholderPrefix().endsWith(simplePrefixForSuffix)) {
            this.simplePrefix = simplePrefixForSuffix;
        } else {
            this.simplePrefix = placeholderSupport.getPlaceholderPrefix();
        }
        placeholderResolver = placeholderSupport.getResolver();
    }
/** 获取PlaceholderSupport */
@Override
    public PlaceholderSupport getPlaceholderSupport() {
        return placeholderSupport;
    }

    /**
     * 解析输入字符串中的所有占位符。
     * 遍历字符串，找到每个占位符，递归解析其键和值，并替换原位置。
     *
     * @param value 待解析的原始字符串
     * @return 解析后的字符串
     */
    @Override
    public String resolvePlaceholders(String value) {
        // 查找第一个占位符前缀的位置
        int startIndex = value.indexOf(placeholderSupport.getPlaceholderPrefix());
        if (startIndex == -1) {
            return value;
        }
        StringBuilder result = new StringBuilder(value);
        while (startIndex != -1) {
            // 查找占位符结束索引（匹配后缀）
            int endIndex = findPlaceholderEndIndex(result, startIndex);
            if (endIndex != -1) {
                // 提取占位符内容（不包含前后缀）
                String placeholder = result.substring(startIndex + placeholderSupport.getPlaceholderPrefix().length(), endIndex);
                String originalPlaceholder = placeholder;
                
                // 初始化循环引用检查集合
                if (visitedPlaceholders == null) {
                    visitedPlaceholders = new HashSet<>(4);
                }
                // 检查是否已存在循环引用
                if (!visitedPlaceholders.add(originalPlaceholder)) {
                    throw new IllegalArgumentException(
                            "Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
                }
                
                // 递归解析占位符键中可能包含的其他占位符
                placeholder = parseStringValue(placeholder, placeholderSupport.getResolver(), visitedPlaceholders);
                
                // 尝试获取解析后的值
                String propVal = placeholderResolver.resolvePlaceholder(placeholder);
                
                // 如果值为空且配置了分隔符，尝试解析默认值
                if (propVal == null && placeholderSupport.getValueSeparator() != null) {
                    int separatorIndex = placeholder.indexOf(placeholderSupport.getValueSeparator());
                    if (separatorIndex != -1) {
                        String actualPlaceholder = placeholder.substring(0, separatorIndex);
                        String defaultValue = placeholder.substring(separatorIndex + placeholderSupport.getValueSeparator().length());
                        propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
                        if (propVal == null) {
                            propVal = defaultValue;
                        }
                    }
                }
                
                if (propVal != null) {
                    // 递归解析解析后的值中可能包含的其他占位符
                    propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
                    // 替换原始字符串中的占位符部分为解析后的值
                    result.replace(startIndex, endIndex + placeholderSupport.getPlaceholderSuffix().length(), StringUtils.defaultString(propVal, ""));
                    if (log.isTraceEnabled()) {
                        log.trace("Resolved placeholder '" + placeholder + "'");
                    }
                    // 更新起始索引，继续搜索后续占位符
                    startIndex = result.indexOf(placeholderSupport.getPlaceholderPrefix(), startIndex + propVal.length());
                } else if (placeholderSupport.isIgnoreUnresolvablePlaceholders()) {
                    // 如果忽略未解析的占位符，跳过当前占位符继续搜索
                    startIndex = result.indexOf(placeholderSupport.getPlaceholderPrefix(), endIndex + placeholderSupport.getPlaceholderSuffix().length());
                } else {
                    // 抛出异常，表示无法解析占位符
                    throw new IllegalArgumentException("Could not resolve placeholder '" +
                            placeholder + "'" + " in value \"" + value + "\"");
                }
                // 移除当前占位符，允许后续递归再次使用
                visitedPlaceholders.remove(originalPlaceholder);
            } else {
                startIndex = -1;
            }
        }
        return result.toString();
    }

    /**
     * 向动态解析器添加新的属性值。
     *
     * @param name  属性名称
     * @param value 属性值
     */
    @Override
    public void add(String name, Object value) {
        if (placeholderResolver instanceof PlaceholderDynamicResolver) {
            ((PlaceholderDynamicResolver) placeholderResolver).add(name, value);
        }
    }

    /**
     * 从动态解析器中移除指定的属性。
     *
     * @param name 属性名称
     */
    @Override
    public void remove(String name) {
        if (placeholderResolver instanceof PlaceholderDynamicResolver) {
            ((PlaceholderDynamicResolver) placeholderResolver).remove(name);
        }
    }

    /**
     * 设置占位符解析器（当前实现为空，保留接口扩展性）。
     *
     * @param placeholderResolver 新的占位符解析器
     */
    @Override
    public void setPlaceholderResolver(PlaceholderResolver placeholderResolver) {

    }

    /**
     * 解析字符串中的占位符，支持递归处理和循环引用检测。
     * 此方法主要用于处理占位符键本身或解析后的值中包含的嵌套占位符。
     *
     * @param value                 待解析的字符串
     * @param placeholderResolver   占位符解析器
     * @param visitedPlaceholders   已访问的占位符集合，用于防止循环引用
     * @return 解析后的字符串
     */
    protected String parseStringValue(
            String value, PlaceholderResolver placeholderResolver, Set<String> visitedPlaceholders) {

        int startIndex = value.indexOf(placeholderSupport.getPlaceholderPrefix());
        if (startIndex == -1) {
            return value;
        }

        StringBuilder result = new StringBuilder(value);
        while (startIndex != -1) {
            int endIndex = findPlaceholderEndIndex(result, startIndex);
            if (endIndex != -1) {
                String placeholder = result.substring(startIndex + placeholderSupport.getPlaceholderPrefix().length(), endIndex);
                String originalPlaceholder = placeholder;
                if (visitedPlaceholders == null) {
                    visitedPlaceholders = new HashSet<>(4);
                }
                if (!visitedPlaceholders.add(originalPlaceholder)) {
                    throw new IllegalArgumentException(
                            "Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
                }
                // 递归解析占位符键中的嵌套占位符
                placeholder = parseStringValue(placeholder, placeholderResolver, visitedPlaceholders);
                // 获取解析后的值
                String propVal = placeholderResolver.resolvePlaceholder(placeholder);
                if (propVal == null && placeholderSupport.getValueSeparator() != null) {
                    int separatorIndex = placeholder.indexOf(placeholderSupport.getValueSeparator());
                    if (separatorIndex != -1) {
                        String actualPlaceholder = placeholder.substring(0, separatorIndex);
                        String defaultValue = placeholder.substring(separatorIndex + placeholderSupport.getValueSeparator().length());
                        propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
                        if (propVal == null) {
                            propVal = defaultValue;
                        }
                    }
                }
                if (propVal != null) {
                    // 递归解析解析后的值中的嵌套占位符
                    propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
                    result.replace(startIndex, endIndex + placeholderSupport.getPlaceholderSuffix().length(), propVal);
                    if (log.isTraceEnabled()) {
                        log.trace("Resolved placeholder '" + placeholder + "'");
                    }
                    startIndex = result.indexOf(placeholderSupport.getPlaceholderPrefix(), startIndex + propVal.length());
                } else if (placeholderSupport.isIgnoreUnresolvablePlaceholders()) {
                    // 跳过未解析的占位符
                    startIndex = result.indexOf(placeholderSupport.getPlaceholderPrefix(), endIndex + placeholderSupport.getPlaceholderSuffix().length());
                } else {
                    throw new IllegalArgumentException("Could not resolve placeholder '" +
                            placeholder + "'" + " in value \"" + value + "\"");
                }
                visitedPlaceholders.remove(originalPlaceholder);
            } else {
                startIndex = -1;
            }
        }
        return result.toString();
    }

    /**
     * 查找占位符的结束索引。
     * 支持嵌套结构（如 ${a: ${b}}），通过计数嵌套层级来确定真正的结束位置。
     *
     * @param buf      字符序列
     * @param startIndex 占位符开始索引
     * @return 占位符结束索引，若未找到则返回 -1
     */
    private int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
        int index = startIndex + placeholderSupport.getPlaceholderPrefix().length();
        int withinNestedPlaceholder = 0;
        while (index < buf.length()) {
            // 如果匹配到后缀
            if (StringUtils.substringMatch(buf, index, placeholderSupport.getPlaceholderSuffix())) {
                if (withinNestedPlaceholder > 0) {
                    // 如果是嵌套层内的后缀，减少计数
                    withinNestedPlaceholder--;
                    index = index + placeholderSupport.getPlaceholderSuffix().length();
                } else {
                    // 否则是外层结束，返回索引
                    return index;
                }
            } else if (StringUtils.substringMatch(buf, index, this.simplePrefix)) {
                // 如果遇到简化前缀（嵌套占位符的开始），增加计数
                withinNestedPlaceholder++;
                index = index + this.simplePrefix.length();
            } else {
                index++;
            }
        }
        return -1;
    }

}
