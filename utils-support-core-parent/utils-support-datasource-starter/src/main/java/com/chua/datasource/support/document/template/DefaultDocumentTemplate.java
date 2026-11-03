// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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

    /**
     * 获取类型
    */
    @Override
    public String getType() {
        return "default";
    }

    /**
     * htmltemplate路径
    */
    @Override
    protected String htmlTemplatePath() {
        return "document/templates/default/index.html";
    }

    /**
     * markdowntemplate路径
    */
    @Override
    protected String markdownTemplatePath() {
        return "document/templates/default/document.markdown";
    }
}
