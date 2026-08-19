package com.chua.datasource.support.document.template;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

/**
 * 默认文档模板（E10 风格交互 HTML + 标准 Markdown）。
 *
 * <p>模板文件位于 datasource-starter：</p>
 * <ul>
 *   <li>{@code document/templates/default/index.html}</li>
 *   <li>{@code document/templates/default/document.markdown}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDefault
@Spi({"default", "DEFAULT"})
public class DefaultDocumentTemplate extends AbstractClasspathDocumentTemplate {

    @Override
    /** 获取Type */
    public String getType() {
        return "default";
    }

    @Override
    /** HtmlTemplatePath */
    protected String htmlTemplatePath() {
        return "document/templates/default/index.html";
    }

    @Override
    /** MarkdownTemplatePath */
    protected String markdownTemplatePath() {
        return "document/templates/default/document.markdown";
    }
}
