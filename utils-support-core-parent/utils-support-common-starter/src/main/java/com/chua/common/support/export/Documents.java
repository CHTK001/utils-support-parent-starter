package com.chua.common.support.export;

import com.chua.common.support.export.builder.DataExportBuilder;
import com.chua.common.support.export.builder.TemplateExportBuilder;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 统一门面 —— 文档导出的唯一入口。
 *
 * <p>提供两条链路，由 {@code template(...)} 方法切换：</p>
 *
 * <pre>{@code
 * // 接口一：List → 文档（注解解析 SPI 驱动，无需模板）
 * Documents.of(list)
 *           .sheet("用户")
 *           .output(new File("/tmp/user.xlsx"))
 *           .export();
 *
 * // 接口二：模板替换（List + 标量 → 模板 → 文档）
 * Documents.of(list)
 *           .template(Path.of("templates/user-report.docx"))
 *           .put("title", "2024 年度报表")
 *           .put("auditor", "张三")
 *           .bind("rows", list)              // 循环块
 *           .output(Path.of("/tmp/user-report.docx"))
 *           .export();
 * }</pre>
 *
 * <p>两个 Builder 均支持链式调用；{@link DataExportBuilder#template(String)} 返回
 * {@link TemplateExportBuilder} 即完成模式切换，后续链式调用在模板模式下继续。</p>
 *
 * <p><b>模板语法</b>（xlsx / docx / pdf 一致，前后缀默认 {@code {{ }} }）：</p>
 * <pre>{@code
 * {{key}}              标量：put("key", value)
 * {{a.b.c}}            嵌套属性路径，任意深度
 * {{items.0}}          集合按下标
 * {{_row.field}}       循环行内取未导出为列的字段
 * {{@index}}           循环内元信息（index / first / last）
 * {{#tag}} … {{/tag}}  循环块，数据由 bind("tag", rows) 绑定
 * }</pre>
 *
 * <p>流归属遵循"谁打开谁关闭"：默认不关闭调用方传入的流，需要时打开
 * {@link DataExportBuilder#autoCloseStream(boolean)}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Documents {

    private Documents() {
    }

    /**
     * 以数据行创建导出（非模板模式）。
     *
     * @param rows 行数据：{@code List}、数组、{@code Iterable}、{@code Stream} 或单个对象
     * @return 非模板构建器
     */
    public static DataExportBuilder of(Object rows) {
        return new DataExportBuilder(rows);
    }

    /**
     * 以数据行类型创建空数据导出（仅导出表头）。
     *
     * @param type 数据行类型
     * @return 非模板构建器
     */
    public static DataExportBuilder ofType(Class<?> type) {
        return new DataExportBuilder(List.of()).type(type);
    }

    /**
     * 直接创建模板构建器。
     *
     * @param template 模板路径 / 文件 / 字节
     * @return 模板构建器
     */
    public static TemplateExportBuilder ofTemplate(String template) {
        return new TemplateExportBuilder().template(template);
    }

    /**
     * 直接创建模板构建器（文件系统路径）。
     *
     * @param template 模板路径
     * @return 模板构建器
     */
    public static TemplateExportBuilder ofTemplate(Path template) {
        return new TemplateExportBuilder().template(template);
    }

    /**
     * 直接创建模板构建器。
     *
     * @param template 模板文件
     * @return 模板构建器
     */
    public static TemplateExportBuilder ofTemplate(File template) {
        return new TemplateExportBuilder().template(template);
    }

    /**
     * 直接创建模板构建器。
     *
     * @param template 模板字节
     * @return 模板构建器
     */
    public static TemplateExportBuilder ofTemplate(byte[] template) {
        return new TemplateExportBuilder().template(template);
    }

    /**
     * 直接创建模板构建器。
     *
     * @param template 模板输入流
     * @return 模板构建器
     */
    public static TemplateExportBuilder ofTemplate(InputStream template) {
        return new TemplateExportBuilder().template(template);
    }
}