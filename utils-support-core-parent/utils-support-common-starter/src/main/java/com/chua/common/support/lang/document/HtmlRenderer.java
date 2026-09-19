package com.chua.common.support.lang.document;

import com.chua.common.support.spi.annotations.Spi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTML 文档导出器。
 *
 * <p>通过 {@link DocumentTemplate} SPI 加载模板文件渲染，不在代码中拼接 HTML 结构。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("html")
public class HtmlRenderer implements DocumentProvider {

    @Override
    /**
     * 获取Type
    */
    public String getType() {
        return "html";
    }

    @Override
    /**
     * 获取Extensions
    */
    public String[] getExtensions() {
        return new String[]{".html", ".htm"};
    }

    @Override
    /**
     * Export
    */
    public void export(DocumentData data, File outputFile, DocumentExportConfig config) {
        DocumentExportConfig resolved = config == null
                ? DocumentExportConfig.builder().format("html").templateType(DocumentTemplateType.DEFAULT).build()
                : config;
        DocumentTemplate template = DocumentTemplate.create(resolved.getTemplateType());
        String content = template.renderHtml(data, resolved);
        write(outputFile, content);
    }

    /**
     * 写入
     * @param outputFile output文件，不允许为 null
     * @param content 内容，不允许为 null
     */
    private void write(File outputFile, String content) {
        try {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new RuntimeException("HTML 导出失败", e);
        }
    }
}
