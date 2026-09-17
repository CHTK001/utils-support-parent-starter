package com.chua.common.support.lang.document;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

/**
* 文档模板 SPI。
*
* <p>每种模板类型（default / swagger 等）独立实现，从 classpath 模板文件渲染，
* 不在 Java 代码中拼接 HTML/Markdown 结构。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi
public interface DocumentTemplate {

    /**
    * 通过 SPI 获取模板实现。
    *
    * @param type 模板类型，对应枚举名小写：default、swagger
    * @return 模板实现
    */
    static DocumentTemplate create(String type) {
        return ServiceProvider.of(DocumentTemplate.class).getExtension(type);
    }

    /**
    * 通过枚举获取模板实现。
    *
    * @param type 模板类型
    * @return 模板实现
    */
    static DocumentTemplate create(DocumentTemplateType type) {
        DocumentTemplateType resolved = type == null ? DocumentTemplateType.DEFAULT : type;
        return create(resolved.name().toLowerCase());
    }

    /**
    * 模板类型名称。
    *
    * @return 名称
    */
    String getType();

    /**
    * 渲染 HTML。
    *
    * @param data   文档数据
    * @param config 导出配置
    * @return HTML 文本
    */
    String renderHtml(DocumentData data, DocumentExportConfig config);

    /**
    * 渲染 Markdown。
    *
    * @param data   文档数据
    * @param config 导出配置
    * @return Markdown 文本
    */
    String renderMarkdown(DocumentData data, DocumentExportConfig config);
}
