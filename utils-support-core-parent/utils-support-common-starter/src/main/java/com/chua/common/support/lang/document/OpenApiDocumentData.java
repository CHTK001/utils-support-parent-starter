package com.chua.common.support.lang.document;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 文档的通用数据模型。
 *
 * <p>与 {@link DocumentData} 并行存在：</p>
 * <ul>
 *     <li>{@link DocumentData} — 面向数据库/表结构文档（TableData / ColumnData）</li>
 *     <li>本类 — 面向 Swagger/OpenAPI 接口文档（tag / endpoint / param）</li>
 * </ul>
 *
 * <p>通用 {@link OpenApiDocumentProvider} 直接消费本模型，
 * 屏蔽 OpenAPI 协议差异 (SpringDoc / native OpenAPI / Knife4j)，
 * 便于在 lang 模块进行测试与离线渲染。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
public class OpenApiDocumentData {

    /**
     * 文档标题（OpenAPI info.title）。
     */
    private String title = "Open API 接口文档";

    /**
     * 文档版本（OpenAPI info.version）。
     */
    private String version = "1.0.0";

    /**
     * 文档描述（OpenAPI info.description）。
     */
    private String description;

    /**
     * Tag 分组列表（与 OpenAPI tags 一致，可缺省）。
     */
    private List<OpenApiTag> tags = new ArrayList<>();

    /**
     * 全量接口列表（按 tag 不重复；tag 缺省归入 "default"）。
     */
    private List<OpenApiEndpoint> endpoints = new ArrayList<>();

    /**
     * 顶部附加信息（约定：home / changelog / 联系 等）。
     */
    private List<OpenApiSection> sections = new ArrayList<>();
}
