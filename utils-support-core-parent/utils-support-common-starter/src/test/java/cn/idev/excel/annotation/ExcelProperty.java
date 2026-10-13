package cn.idev.excel.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试用 FastExcel {@code ExcelProperty} 桩注解 —— 仅声明本框架真实读取的属性子集。
 *
 * <p>目的：在不引入 FastExcel 依赖的前提下，验证
 * {@code ThirdPartyMetaResolver} 的纯反射解析与优先级覆盖行为。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelProperty {

    /**
     * 列标题。
     *
     * @return 列标题
     */
    String[] value() default {};

    /**
     * 列顺序。
     *
     * @return 列顺序，{@code -1} 表示未声明
     */
    int index() default -1;

    /**
     * 日期格式。
     *
     * @return 日期格式
     */
    String dateFormat() default "";

    /**
     * 转换器。
     *
     * @return 转换器类型
     */
    Class<?> converter() default void.class;
}
