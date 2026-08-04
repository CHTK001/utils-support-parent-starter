package com.chua.common.support.lang.document;

import org.jspecify.annotations.NullUnmarked;

/**
 * 文档导出模板类型。
 *
 * <p>决定 {@link DocumentData} 渲染为 HTML / Markdown / Word 时的版式风格。
 * 后续可通过自定义 HTML 模板覆盖默认渲染逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public enum DocumentTemplateType {

    /**
     * 默认数据库文档模板。
     * <p>简洁表格风格，适用于数据库设计文档导出。</p>
     */
    DEFAULT,

    /**
     * Swagger / OpenAPI 风格模板。
     * <p>侧边导航 + 分组展示，视觉接近 Swagger UI / Knife4j 文档页。</p>
     */
    SWAGGER,

    /**
     * Aurora 现代化惊艳模板。
     * <p>深色玻璃拟态 + 渐变光晕 + 侧栏双行导航与实时检索，适合对外展示与分享。</p>
     */
    AURORA
}
