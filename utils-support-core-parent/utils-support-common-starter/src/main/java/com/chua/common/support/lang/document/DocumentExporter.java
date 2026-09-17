package com.chua.common.support.lang.document;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
* 文档导出链式构建器。
*
* <p>用法示例：</p>
* <pre>{@code
* DocumentExporter.of(data)
*     .format("html")
*     .template(DocumentTemplateType.SWAGGER)
*     .output(new File("out/db.html"))
*     .export();
*
* // 同一份数据多格式导出
* DocumentExporter.of(data)
*     .template(DocumentTemplateType.DEFAULT)
*     .format("markdown").output(new File("out/db.md")).export()
*     .format("word").output(new File("out/db.docx")).export();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class DocumentExporter {

    /**
    * 待导出的文档数据
    */
    private final DocumentData data;

    /**
    * 导出格式
    */
    private String format = "html";

    /**
    * 模板类型
    */
    private DocumentTemplateType templateType = DocumentTemplateType.DEFAULT;

    /**
    * 输出文件
    */
    private File outputFile;

    /**
    * 自定义 HTML 模板
    */
    private String customHtmlTemplate;

    /**
    * 扩展参数
    */
    private final Map<String, Object> options = new HashMap<>();

    /**
    * 创建 DocumentExporter 实例
    * @param data data
    */
    private DocumentExporter(DocumentData data) {
        this.data = data;
    }

    /**
    * 以文档数据创建导出器。
    *
    * @param data 文档数据，不可为 null
    * @return 导出器实例
    */
    public static DocumentExporter of(DocumentData data) {
        if (data == null) {
            throw new IllegalArgumentException("DocumentData 不能为空");
        }
        return new DocumentExporter(data);
    }

    /**
    * 设置导出格式。
    *
    * @param format SPI 名称：word / pdf / markdown / html
    * @return this
    */
    public DocumentExporter format(String format) {
        if (format != null && !format.isBlank()) {
            this.format = format.trim().toLowerCase();
        }
        return this;
    }

    /**
    * 设置模板类型。
    *
    * @param templateType 模板枚举
    * @return this
    */
    public DocumentExporter template(DocumentTemplateType templateType) {
        if (templateType != null) {
            this.templateType = templateType;
        }
        return this;
    }

    /**
    * 设置输出文件。
    *
    * @param outputFile 输出路径
    * @return this
    */
    public DocumentExporter output(File outputFile) {
        this.outputFile = outputFile;
        return this;
    }

    /**
    * 设置输出文件路径。
    *
    * @param path 文件路径
    * @return this
    */
    public DocumentExporter output(String path) {
        if (path != null && !path.isBlank()) {
            this.outputFile = new File(path);
        }
        return this;
    }

    /**
    * 设置自定义 HTML 模板内容（覆盖内置模板）。
    *
    * @param htmlTemplate HTML 模板字符串
    * @return this
    */
    public DocumentExporter customHtmlTemplate(String htmlTemplate) {
        this.customHtmlTemplate = htmlTemplate;
        return this;
    }

    /**
    * 追加扩展参数。
    *
    * @param key   参数名
    * @param value 参数值
    * @return this
    */
    public DocumentExporter option(String key, Object value) {
        if (key != null) {
            this.options.put(key, value);
        }
        return this;
    }

    /**
    * 批量设置扩展参数。
    *
    * @param options 参数映射
    * @return this
    */
    public DocumentExporter options(Map<String, Object> options) {
        if (options != null) {
            this.options.putAll(options);
        }
        return this;
    }

    /**
    * 构建当前导出配置快照。
    *
    * @return 导出配置
    */
    public DocumentExportConfig buildConfig() {
        return DocumentExportConfig.builder()
                .format(format)
                .templateType(templateType)
                .outputFile(outputFile)
                .customHtmlTemplate(customHtmlTemplate)
                .options(new HashMap<>(options))
                .build();
    }

    /**
    * 执行导出。
    *
    * @return this，便于继续链式导出其他格式
    */
    public DocumentExporter export() {
        if (outputFile == null) {
            throw new IllegalStateException("未设置输出文件，请先调用 output(...)");
        }
        DocumentProvider provider = DocumentProvider.create(format);
        if (provider == null) {
            throw new IllegalStateException("未找到文档导出实现: " + format);
        }
        DocumentExportConfig config = buildConfig();
        provider.export(data, outputFile, config);
        return this;
    }
}
