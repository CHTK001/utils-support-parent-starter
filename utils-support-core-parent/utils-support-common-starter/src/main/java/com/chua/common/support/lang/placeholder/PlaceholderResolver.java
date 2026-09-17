package com.chua.common.support.lang.placeholder;


/**
* 占位符解析器接口。
* 用于根据给定的占位符名称或属性键，解析并返回对应的值。
* 支持从环境变量、系统属性、配置文件等多种来源获取配置信息。
*
* @author CH
* @since 4.0.0.42
 */
public interface PlaceholderResolver {

    /**
    * 解析指定的占位符。
    * 该方法将尝试查找与给定名称匹配的占位符值。
    * 如果找到匹配项，则返回解析后的字符串；否则返回 null 或原始占位符（取决于具体实现）。
    *
    * @param placeholderName 要解析的占位符名称，例如 "user.name" 或 "${user.name}"。
    * @return 解析后的字符串值；如果未找到匹配项，则可能返回 null。
    */
    String resolvePlaceholder(String placeholderName);

    /**
    * 根据给定的键获取属性值。
    * 该方法直接查询底层属性源，返回与指定键关联的值。
    * 如果该键不存在，则返回 null。
    *
    * @param key 要查询的属性键，例如 "spring.datasource.url"。
    * @return 对应键的属性值；如果键不存在，则返回 null。
    */
    String getProperty(String key);
}
