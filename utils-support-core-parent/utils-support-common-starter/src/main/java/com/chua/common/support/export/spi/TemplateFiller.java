package com.chua.common.support.export.spi;

import com.chua.common.support.export.TemplateContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * 模板填充器 SPI —— 接口二：<b>模板替换</b>。
 *
 * <p>读取用户提供的模板文件（xlsx / docx / pdf），把 {@link TemplateContext} 中的
 * 标量变量与循环表格写入模板，输出填充后的文档。</p>
 *
 * <p><b>模板语法（各实现必须一致）</b>，前后缀由 {@link TemplateContext#config()} 决定，
 * 默认 {@code {{ }} }：</p>
 * <ul>
 *     <li>{@code {{key}}} —— 标量，取自 {@link TemplateContext#variables()}</li>
 *     <li>{@code {{a.b.c}}} —— 嵌套路径，任意深度；集合支持下标 {@code {{items.0}}}</li>
 *     <li>{@code {{@index}}} / {@code {{@first}}} / {@code {{@last}}} —— 循环内元信息</li>
 *     <li>{@code {{#tag}}} … {@code {{/tag}}} —— 循环块，标记本身不产出内容、填充后移除；
 *         块内无数据时整块消失</li>
 * </ul>
 *
 * <p>实现方<b>应当</b>复用 {@link com.chua.common.support.export.support.Placeholders} 解析占位符，
 * 以保证三种格式行为一致；<b>不得关闭</b>传入的输入/输出流（流归属见
 * {@code DataExportBuilder#autoCloseStream(boolean)}）。</p>
 *
 * <p>实现类通过 {@code META-INF/extensions/com.chua.common.support.export.spi.TemplateFiller}
 * 注册。别名即"引擎名"（如 {@code poi} / {@code poi-word} / {@code pdfbox}），也可用
 * {@link #formats()} 声明支持的格式（如 {@code xlsx}）作为别名注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TemplateFiller {

    /**
     * 本实现支持的格式标识集合，如 {@code ["xlsx", "xls"]}。
     *
     * @return 格式标识集合
     */
    Set<String> formats();

    /**
     * 填充模板并写入输出流。实现方<b>不得关闭</b>传入的输入/输出流。
     *
     * @param context  模板上下文
     * @param template 模板输入流
     * @param out      输出流
     * @throws IOException 读写失败
     */
    void fill(TemplateContext context, InputStream template, OutputStream out) throws IOException;

    /**
     * 引擎名，用于显式指定实现。
     *
     * @return 引擎名
     */
    default String engine() {
        return getClass().getSimpleName();
    }
}