package com.chua.common.support.lang.document;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 文档生成配置。
 * <p>
 * 包含生成文档所需的所有参数，通过建造者模式创建。
 * 不同类型的文档生成器（数据库、API）使用不同的配置字段。
 * </p>
 *
 * 配置字段说明：
 * <ul>
 *   <li>{@code type} — 文档类型，对应 {@link DocumentProvider} 的扩展名称，如 {@code "database"}</li>
 *   <li>{@code url} — 对于数据库文档为 JDBC URL，对于 API 文档为 Swagger URL</li>
 *   <li>{@code username}/{@code password} — 数据库登录凭据</li>
 *   <li>{@code driverClass} — JDBC 驱动类全限定名，例如 {@code org.h2.Driver}</li>
 *   <li>{@code options} — 扩展参数，用于传递各实现特有的配置</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.41
 */
@Data
@Builder
public class DocumentConfig {

    /** 文档类型，@Spi 扩展名，如 "database"、"swagger" */
    /**
     * 类型
     */
    private String type;

    /** JDBC URL 或 API 文档地址 */
    /**
     * 地址
     */
    private String url;

    /** 数据库用户名 */
    /**
     * 用户名
     */
    private String username;

    /** 数据库密码 */
    /**
     * 密码
     */
    private String password;

    /** JDBC 驱动类全限定名 */
    private String driverClass;

    /** 扩展参数字段，各实现可自行定义键值含义 */
    private Map<String, Object> options;
}
