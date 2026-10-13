package com.chua.common.support.export.annotation;

import com.chua.common.support.export.meta.ColumnType;
import com.chua.common.support.export.meta.HorizontalAlign;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 列注解 —— 通用文档导出的列定义。
 *
 * <p>本注解是 <b>唯一必须依赖</b> 的列元信息来源：不引入任何第三方注解依赖即可完成
 * 列名、顺序、宽度、类型、格式、对齐等全部定义。</p>
 *
 * <p><b>优先级约定</b>：解析器按 {@code order} 升序依次执行，后解析者覆盖先解析者。
 * 三方注解（如 EasyExcel / FastExcel 的 {@code @ExcelProperty}）作为底层来源，
 * 本注解用于覆盖，最终在无任何注解时由反射推断兜底。</p>
 *
 * <p><b>未声明的判定</b>：字符串以空串、整数以 {@link #UNSET}、枚举以
 * {@link ColumnType#AUTO} / {@link HorizontalAlign#AUTO} 表示"未声明"，不覆盖低优先级来源。</p>
 *
 * <pre>{@code
 * @ExportSheet(name = "用户明细")
 * public class UserVo {
 *
 *     @ExportColumn(name = "用户编号", order = 1, width = 18)
 *     private String code;
 *
 *     @ExportColumn(name = "注册时间", order = 3, type = ColumnType.DATETIME, dateFormat = "yyyy-MM-dd HH:mm:ss")
 *     private LocalDateTime createdAt;
 *
 *     @ExportColumn(order = 4, type = ColumnType.ENUM, enumNames = {"0=禁用", "1=启用"})
 *     private Integer status;
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ExportColumn {

    /**
     * 未声明的整型哨兵值。
     */
    int UNSET = Integer.MIN_VALUE;

    /**
     * 列标题。为空表示未声明，沿用低优先级解析器的结果。
     *
     * @return 列标题
     */
    String name() default "";

    /**
     * 列顺序，小的在前。{@link #UNSET} 表示未声明。
     *
     * @return 列顺序
     */
    int order() default UNSET;

    /**
     * 列宽度（字符数），{@link #UNSET} 表示未声明；0 表示自动计算。
     *
     * @return 列宽
     */
    int width() default UNSET;

    /**
     * 是否忽略该列。
     *
     * @return 忽略标记
     */
    boolean ignore() default false;

    /**
     * 列的数据类型。{@link ColumnType#AUTO} 表示未声明，按属性类型推断。
     *
     * @return 列类型
     */
    ColumnType type() default ColumnType.AUTO;

    /**
     * 日期/时间格式化表达式，如 {@code yyyy-MM-dd}。为空表示未声明。
     *
     * @return 日期格式
     */
    String dateFormat() default "";

    /**
     * 枚举/字典映射，格式 {@code "值=文本,值=文本"}。为空表示未声明。
     *
     * @return 枚举映射表
     */
    String[] enumNames() default {};

    /**
     * 值前缀。
     *
     * @return 前缀
     */
    String prefix() default "";

    /**
     * 值后缀。
     *
     * @return 后缀
     */
    String suffix() default "";

    /**
     * 空值时的默认值。
     *
     * @return 默认值
     */
    String defaultValue() default "";

    /**
     * 水平对齐。{@link HorizontalAlign#AUTO} 表示未声明。
     *
     * @return 水平对齐
     */
    HorizontalAlign align() default HorizontalAlign.AUTO;

    /**
     * 是否自动换行。
     *
     * @return 换行标记
     */
    boolean wrap() default false;

    /**
     * 自定义转换器标识：SPI 名称，或全限定类名。为空表示未声明。
     *
     * @return 转换器标识
     */
    String converter() default "";

    /**
     * 取值表达式，支持 {@code a.b.c} 形式的嵌套属性路径。为空表示未声明。
     *
     * @return 取值表达式
     */
    String expression() default "";

    /**
     * 模板模式下该列的占位符别名，如 {@code tag = "amount"} 时模板里可写 {@code {{amount}}}。
     * 为空表示使用属性名（属性名本身始终可用）。
     *
     * @return 模板标签名
     */
    String tag() default "";

    /**
     * 精度（数值类型专用），-1 表示未声明。
     *
     * @return 精度
     */
    int scale() default -1;
}