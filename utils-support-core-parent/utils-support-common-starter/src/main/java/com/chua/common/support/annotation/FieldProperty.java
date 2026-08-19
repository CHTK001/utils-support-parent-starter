package com.chua.common.support.annotation;

import com.chua.common.support.converter.FieldConverter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段属性映射注解 — 定义 POJO 字段到数据列的映射规则。
 *
 * <p>用于控制 {@link com.chua.common.support.utils.BeanUtils#objectToMap(Object)} 时的字段映射行为，
 * 也用于文件系统读写（CSV、Excel、JSON 等）中的字段转换。</p>
 *
 * <h2>属性说明</h2>
 * <ul>
 *   <li><b>value / name</b> — 映射后的列名，默认使用字段名</li>
 *   <li><b>fmt</b> — 日期格式（如 {@code "yyyy-MM-dd HH:mm:ss"}）</li>
 *   <li><b>defaultValue</b> — 默认值，支持 {@code #{key}} 从 {@link FieldMappingContext} 获取上下文数据</li>
 *   <li><b>reader</b> — 读取转换器（读入时：字符串 → 对象字段）</li>
 *   <li><b>writer</b> — 写入转换器（写出时：对象字段 → 字符串）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * public class User {
 *     @FieldProperty("user_name")
 *     private String name;
 *
 *     @FieldProperty(fmt = "yyyy-MM-dd")
 *     private LocalDate birthDate;
 *
 *     @FieldProperty(defaultValue = "#{now}")
 *     private LocalDate createTime;
 *
 *     @FieldProperty(writer = MoneyConverter.class)
 *     private BigDecimal salary;
 * }
 * }</pre>
 *
 * @since 4.0.0.42
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FieldProperty {

    /**
     * 映射后的列名（默认使用字段名）。
     *
     * @return 列名
     */
    String value() default "";

    /**
     * 别名，同 {@link #value()}。
     *
     * @return 列名
     */
    String name() default "";

    /**
     * 日期格式（如 {@code "yyyy-MM-dd HH:mm:ss"}）。
     *
     * @return 格式模式
     */
    String fmt() default "";

    /**
     * 默认值。
     * <p>当字段值为 {@code null} 时使用此默认值。支持 {@code #{key}} 表达式从
     * {@link FieldMappingContext#getContext()} 中获取值。</p>
     *
     * @return 默认值
     */
    String defaultValue() default "";

    /**
     * 读取转换器（读入场景：文件/数据库字符串 → 对象字段）。
     *
     * @return 读取转换器类型
     */
    Class<? extends FieldConverter> reader() default FieldConverter.class;

    /**
     * 写入转换器（写出场景：对象字段 → 文件/数据库字符串）。
     * <p>转换器输出优先级：converter 结果 > 上下文 #{key} 值 > 字段原始值</p>
     *
     * @return 写入转换器类型
     */
    Class<? extends FieldConverter> writer() default FieldConverter.class;
}
