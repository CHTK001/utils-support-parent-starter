package com.chua.datasource.support.document.template;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Screw 工业螺丝风格数据库文档模板。
 *
 * <p>灵感来源于机械装配图：暗色底 + 六角螺丝铆接的分区导航 + 数据表「拆解视图」。
 * 每个表是一块铆在图纸上的钢板，字段是拧上去的螺丝，主键/外键用螺纹标记区分。
 * 单文件、无外链，适合数据库设计评审与交付存档。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"screw", "Screw", "industrial", "INDUSTRIAL"})
public class ScrewDocumentTemplate extends AbstractClasspathDocumentTemplate {

    @Override
    public String getType() {
        return "screw";
    }

    @Override
    protected String htmlTemplatePath() {
        return "document/templates/screw/index.html";
    }

    @Override
    protected String markdownTemplatePath() {
        return "document/templates/screw/document.markdown";
    }
}
