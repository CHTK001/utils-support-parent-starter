package com.chua.datasource.support.document.template;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Swagger / 打开api 风格文档模板。
 *
 * <p>模板文件位于 datasource-starter：</p>
 * <ul>
 *   <li>{@code document/templates/swagger/index.html}</li>
 *   <li>{@code document/templates/swagger/document.markdown}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"swagger", "SWAGGER"})
public class SwaggerDocumentTemplate extends AbstractClasspathDocumentTemplate {

    @Override
    /**
     * 获取类型
    */
    public String getType() {
        return "swagger";
    }

    @Override
    /**
     * htmltemplate路径
    */
    protected String htmlTemplatePath() {
        return "document/templates/swagger/index.html";
    }

    @Override
    /**
     * markdowntemplate路径
    */
    protected String markdownTemplatePath() {
        return "document/templates/swagger/document.markdown";
    }
}
