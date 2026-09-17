package com.chua.common.support.document;

import com.chua.common.support.document.request.GenerateRequest;
import com.chua.common.support.document.result.GenerateResult;
import com.chua.common.support.spi.annotations.Spi;

import java.util.Map;

/**
* 文档生成器 SPI 接口。
*
* <p>定义统一的文档生成契约，支持 Word、PDF、Markdown、图片等多种格式的生成。</p>
*
* <p>核心操作：</p>
* <ul>
*   <li>{@link #generate(GenerateRequest)} — 生成文档</li>
*   <li>{@link #getType()} — 获取文档类型</li>
*   <li>{@link #getExtensions()} — 获取支持的文件扩展名</li>
* </ul>
*
* <p>使用示例：</p>
* <pre>{@code
* // 通过 SPI 创建文档生成器
* DocumentProvider provider = DocumentProvider.create("word");
*
* // 生成文档
* GenerateResult result = provider.generate(GenerateRequest.builder()
*         .fileName("report.docx")
*         .content(List.of("标题", "内容"))
*         .build());
* }</pre>
*
* @author CH
* @since 1.0
 */
@Spi
public interface DocumentProvider {

    /**
    * 通过 SPI 创建文档生成器实例。
    *
    * @param type 文档类型名称（如 "word"、"pdf"、"markdown"、"image"）
    * @return DocumentProvider 实例
    */
    static DocumentProvider create(String type) {
        return com.chua.common.support.spi.ServiceProvider.of(DocumentProvider.class)
                .getExtension(type);
    }

    /**
    * 获取文档类型名称。
    *
    * @return 类型名称（如 "word"、"pdf"、"markdown"、"image"）
    */
    String getType();

    /**
    * 获取支持的文件扩展名。
    *
    * @return 扩展名数组（如 [".docx", ".doc"]）
    */
    String[] getExtensions();

    /**
    * 生成文档。
    *
    * @param request 生成请求
    * @return 生成结果
    */
    GenerateResult generate(GenerateRequest request);
}
