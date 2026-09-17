package com.chua.common.support.lang.placeholder;



/**
* 属性占位符解析器接口。
* 用于解析文本中的占位符，支持添加、移除和设置自定义的占位符解析逻辑。
*
* @author CH
* @since 4.0.0.42
 */
public interface PropertyResolver {
    /**
    * 解析文本中的所有占位符。
    * 将文本中形如 ${key} 或 #key# 的占位符替换为对应的值。
    *
    * @param text 包含占位符的原始文本字符串。
    * @return 解析后的文本字符串；如果输入为 null 则返回 null。
    */
    String resolvePlaceholders(String text);

    /**
    * 获取当前使用的占位符支持对象。
    * 该对象通常封装了占位符的语法定义和默认解析行为。
    *
    * @return 当前的 PlaceholderSupport 实例。
    */
    PlaceholderSupport getPlaceholderSupport();

    /**
    * 向解析器中添加一个属性键值对。
    * 添加的属性将在后续调用 resolvePlaceholders 时被用于替换占位符。
    *
    * @param name  属性的名称（即占位符中的 key）。
    * @param value 属性的值。
    */
    void add(String name, Object value);

    /**
    * 从解析器中移除指定的属性。
    * 移除后，该属性名将无法再被解析。
    *
    * @param headerName 要移除的属性名称。
    */
    void remove(String headerName);

    /**
    * 设置自定义的占位符解析器。
    * 当遇到无法直接通过属性表解析的占位符时，将委托给此解析器进行处理。
    *
    * @param placeholderResolver 自定义的占位符解析器实现。
    */
    void setPlaceholderResolver(PlaceholderResolver placeholderResolver);
}
