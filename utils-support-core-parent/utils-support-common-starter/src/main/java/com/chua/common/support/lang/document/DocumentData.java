package com.chua.common.support.lang.document;

import lombok.Builder;
import lombok.Data;

import java.util.*;
import org.jspecify.annotations.NullUnmarked;

/**
 * 文档生成结果数据。
 * <p>
 * 包含文档元信息（数据库名称、产品名、版本等）和所有表结构的集合。
 * 通过建造者模式创建，最终可渲染为 Markdown、HTML 等格式。
 * </p>
 *
 * @author CH
 * @since 4.0.0.41
 */
@NullUnmarked
@Data
@Builder
public class DocumentData {

    /** 数据库名称（JDBC 连接 catalog） */
    private String databaseName;

    /** 数据库产品名称，例如 H2、MySQL、PostgreSQL */
    private String productName;

    /** 数据库产品版本号 */
    private String productVersion;

    /** JDBC 连接 URL */
    /**
     * 地址
     */
    private String url;

    /** 文档标题，默认值为 "数据库设计文档" */
    @Builder.Default
    private String title = "数据库设计文档";

    /** 文档描述说明 */
    /**
     * 描述
     */
    private String description;

    /** 文档版本号，默认 "1.0.0" */
    @Builder.Default
    /**
     * 版本号
     */
    private String version = "1.0.0";

    /** 所有表结构的集合 */
    @Builder.Default
    private List<TableData> tables = new ArrayList<>();
}
