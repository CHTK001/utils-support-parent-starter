   package com.chua.ast.support.annotation;


import java.lang.annotation.*;

/**
 * 参数默认值注解，编译期利用 AST 技术在方法体开头插入 空 检查及默认值赋值代码
 *
 * <p>该注解会在编译期解析 {@code @DefaultValue} 注解的值，生成对应的 null 检查 + 默认值赋值语句。
 * 支持多种数据类型：字符串、基本类型及其包装类、枚举、数组等。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * public void process(@DefaultValue("default_role") String role) {
 *     // 转换后：
 *     // if (role == null) { role = "default_role"; }
 *     // ...原始方法体...
 * }
 *
 * // 基本类型不支持 null，参数已赋值则不变
 * public void calc(@DefaultValue("42") int count) {
 *     // 基本类型不做 null 检查
 * }
 *
 * // 数组类型
 * public void setNames(@DefaultValue({"admin", "user"}) String[] names) {
 *     // 转换后：if (names == null) { names = new String[]{"admin", "user"}; }
 * }
 *
 * // 枚举类型
 * public void setLevel(@DefaultValue("HIGH") LogLevel level) {
 *     // 转换后：if (level == null) { level = LogLevel.HIGH; }
 * }
 * }</pre>空) { 级别 = 日志级别.HIGH; }
 * }
 * }</pre>
 *
 * <p>支持的数据类型说明：</p>
 * <ul>
 *   <li>String：直接使用注解值作为默认值</li>
 *   <li>基本类型及包装类：int/Integer/long/Long/double/Double/boolean/Boolean 等自动转换</li>
 *   <li>数组类型：使用 {@code @DefaultValue({"val1", "val2"})} 语法</li>
 *   <li>枚举类型：使用 {@code @DefaultValue("ENUM_CONSTANT")} 语法</li>
 * </ul>
 *
 * @author CH
 * @since 2024
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface DefaultValue {

    /**
     * 默认值列表
     *
     * <ul>
     *   <li>String: {@code @DefaultValue("default")}</li>
     *   <li>int/Integer: {@code @DefaultValue("42")}</li>
     *   <li>boolean/Boolean: {@code @DefaultValue("true")}</li>
     *   <li>long/Long: {@code @DefaultValue("100L")}</li>
     *   <li>double/Double: {@code @DefaultValue("3.14")}</li>
     *   <li>String[]: {@code @DefaultValue({"a", "b"})}</li>
     *   <li>枚举类型：{@code @DefaultValue("ENUM_CONSTANT")} 使用枚举常量名称</li>
     * </ul>
     *
     * @return 默认值字符串数组
     */
    String[] value();
}
