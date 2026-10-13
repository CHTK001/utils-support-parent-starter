package com.chua.common.support.export.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 文档/工作表注解 —— 描述整体结构。
 *
 * <p>仅在类型上使用。所有属性均可选，未显式设置时由 {@link ExportConfig} 或实现方决定。</p>
 *
 * <pre>{@code
 * @ExportSheet(name = "用户明细", title = "2024 年度用户明细", headerRow = 2, rowsPerSheet = 50000)
 * public class UserVo { }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExportSheet {

    /**
     * 工作表名称（Excel）/ 文档小节名。为空表示使用类型简单名。
     *
     * @return 工作表名
     */
    String name() default "";

    /**
     * 文档大标题。为空表示不生成标题。
     *
     * @return 标题
     */
    String title() default "";

    /**
     * 表头行索引，从 1 开始。1 表示不预留标题行。
     *
     * @return 表头行号
     */
    int headerRow() default 1;

    /**
     * 是否生成表头。
     *
     * @return 表头标记
     */
    boolean header() default true;

    /**
     * 是否冻结表头。
     *
     * @return 冻结标记
     */
    boolean freezeHeader() default true;

    /**
     * 是否启用自动筛选。
     *
     * @return 筛选标记
     */
    boolean autoFilter() default true;

    /**
     * 单个工作表最大行数，0 表示不拆分。
     *
     * @return 单表最大行数
     */
    int rowsPerSheet() default 0;
}