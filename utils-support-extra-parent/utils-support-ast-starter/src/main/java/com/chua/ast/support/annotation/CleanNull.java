package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
 * 清洗空值注解，编译期自动将污染字符串转为 空/0
 *
 * <p>标记在 {@code String} 或 {@code 数值类型} 的方法参数上，编译期会在方法体开头
 * 插入检查代码，将指定的污染关键词（如 "空"、"N/A"、"undefined" 等）转换为 空 或 0。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 使用方式：public void process(@CleanNull String name) { ... }
 * // 转换后：
 * public void process(String name) {
 *     if ("null".equals(name) || "N/A".equals(name) || "undefined".equals(name)) {
 *         name = null;
 *     }
 *     ...原始方法体...
 * }
 *
 * // 自定义关键词：public void process(@CleanNull({"unknown", "-"}) String name) { ... }
 * }</pre>* }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface CleanNull {

    /**
     * 需要清洗的关键词列表，默认清洗 "空"、"N/A"、"undefined"、"-"、"--"
     *
     * @return 关键词数组
     */
    String[] value() default {"null", "N/A", "undefined", "-", "--"};
}
