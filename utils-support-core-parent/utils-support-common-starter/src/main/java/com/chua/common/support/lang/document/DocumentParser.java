package com.chua.common.support.lang.document;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
* 文档解析器 SPI 接口。
*
* <p>从数据源（数据库、Swagger 等）解析出 {@link DocumentData}。</p>
*
* <p>通过 SPI 加载不同数据源的实现：</p>
* <ul>
*   <li>{@code "database"} — JDBC 数据库元数据解析</li>
*   <li>{@code "swagger"} — OpenAPI/Swagger 文档解析</li>
* </ul>
*
* <p>使用示例：</p>
* <pre>{@code
* DocumentParser parser = DocumentParser.create("database");
* DocumentData data = parser.parse(DocumentConfig.builder()
*         .url("jdbc:h2:mem:testdb")
*         .username("sa")
*         .password("")
*         .build());
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi
public interface DocumentParser {

    /**
    * 通过 SPI 创建解析器实例。
    *
    * @param type 数据源类型（"database"、"swagger" 等）
    * @return DocumentParser 实例
    */
    static DocumentParser create(String type) {
        return ServiceProvider.of(DocumentParser.class).getExtension(type);
    }

    /**
    * 解析数据源，生成文档数据。
    *
    * @param config 文档配置
    * @return 文档数据
    */
    DocumentData parse(DocumentConfig config);
}
