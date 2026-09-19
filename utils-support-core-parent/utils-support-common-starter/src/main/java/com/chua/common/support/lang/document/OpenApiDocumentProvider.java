package com.chua.common.support.lang.document;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
 * OpenAPI 文档导出器 SPI 接口。
 *
 * <p>与 {@link DocumentProvider} 并行：</p>
 * <ul>
 *     <li>{@link DocumentProvider} — 面向数据库文档 ({@link DocumentData})</li>
 *     <li>本接口 — 面向 OpenAPI 接口文档 ({@link OpenApiDocumentData})</li>
 * </ul>
 *
 * <p>典型实现：HTML 单页导出（模仿泛微 E10 OpenAPI 风格），Markdown / Word 后续可扩展。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * OpenApiDocumentProvider exporter = OpenApiDocumentProvider.create("html");
 * exporter.export(openApiData, new File("./api-doc.html"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi
public interface OpenApiDocumentProvider {

    /**
     * 静态工厂：通过 SPI 创建导出器。
     *
     * @param type 导出格式（"html" / "markdown" / "word"）
     * @return 导出器实例
     */
    static OpenApiDocumentProvider create(String type) {
        return ServiceProvider.of(OpenApiDocumentProvider.class).getExtension(type);
    }

    /**
     * 导出格式名称。
     * @return 结果字符串
     */
    String getType();

    /**
     * 支持的文件扩展名（[".html"] / [".md"] / [".docx"]）。
     * @return 字符串 对象
     */
    String[] getExtensions();

    /**
     * 同步导出。
     *
     * @param data       OpenAPI 文档数据
     * @param outputFile 输出文件
     */
    void export(OpenApiDocumentData data, File outputFile);
}
