package com.chua.datasource.support.document.template;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Aurora 现代化文档模板。
 *
 * <p>模板文件位于 datasource-starter：</p>
 * <ul>
 *   <li>{@code document/templates/aurora/index.html}</li>
 *   <li>{@code document/templates/aurora/document.markdown}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"aurora", "AURORA", "modern", "MODERN"})
public class AuroraDocumentTemplate extends AbstractClasspathDocumentTemplate {

    /**
     * 获取类型
    */
    @Override
    public String getType() {
        return "aurora";
    }

    /**
     * htmltemplate路径
    */
    @Override
    protected String htmlTemplatePath() {
        return "document/templates/aurora/index.html";
    }

    /**
     * markdowntemplate路径
    */
    @Override
    protected String markdownTemplatePath() {
        return "document/templates/aurora/document.markdown";
    }
}
