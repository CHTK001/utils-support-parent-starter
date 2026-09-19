package com.chua.datasource.support.document.template;

import com.chua.common.support.lang.document.DocumentData;
import com.chua.common.support.lang.document.DocumentExportConfig;
import com.chua.common.support.spi.annotations.Spi;

/**
 * Stripe 风格数据库文档模板。
 *
 * <p>灵感来源于 Stripe/ReadMe 开发者文档：左侧表导航 + 平滑滚动、
 * 语义色标签（PK=橙/FK=青/可空=红/不可空=绿）、
 * 深色 SQL 代码块、搜索过滤、响应式布局，单文件无外链。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi({"stripe", "Stripe", "developer-doc", "DEVELOPER_DOC"})
public class StripeDocumentTemplate extends AbstractClasspathDocumentTemplate {

    @Override
    public String getType() {
        return "stripe";
    }

    @Override
    protected String htmlTemplatePath() {
        return "document/templates/stripe/index.html";
    }

    @Override
    protected String markdownTemplatePath() {
        return "document/templates/stripe/document.markdown";
    }

    /**
     * 重写 构建变量，补充 stripe 模板专用的导出时间占位符。
     */
    @Override
    protected java.util.Map<String, Object> buildVariables(DocumentData data, DocumentExportConfig config) {
        java.util.Map<String, Object> vars = super.buildVariables(data, config);
 // 导出时间 已在父类中设置，此处确保格式统一
        return vars;
    }
}
