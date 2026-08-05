package com.chua.common.support.lang.document;

import lombok.Builder;
import lombok.Data;

import java.io.File;
import java.util.Map;

/**
 * 文档导出配置。
 *
 * <p>封装导出格式、模板类型、输出路径及扩展参数，供 {@link DocumentExporter} 链式调用使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class DocumentExportConfig {

    /**
     * 导出格式 SPI 名称，如 word、pdf、markdown、html
     */
    private String format;

    /**
     * 模板类型，默认 {@link DocumentTemplateType#DEFAULT}
     */
    @Builder.Default
    private DocumentTemplateType templateType = DocumentTemplateType.DEFAULT;

    /**
     * 输出文件
     */
    private File outputFile;

    /**
     * 自定义 HTML 模板内容（非空时覆盖内置模板）
     */
    private String customHtmlTemplate;

    /**
     * 扩展参数
     */
    private Map<String, Object> options;
}
