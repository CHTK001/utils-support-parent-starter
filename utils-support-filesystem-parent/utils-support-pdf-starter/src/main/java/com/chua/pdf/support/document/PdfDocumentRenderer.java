package com.chua.pdf.support.document;

import com.chua.common.support.lang.document.*;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * PDF 文档导出器。
 *
 * <p>通过 {@link DocumentTemplate} 渲染 HTML 后，调用 wkhtmltopdf 转 PDF。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("pdf")
public class PdfDocumentRenderer implements DocumentProvider {

    @Override
    /** 获取Type */
    public String getType() {
        return "pdf";
    }

    @Override
    /** 获取Extensions */
    public String[] getExtensions() {
        return new String[]{".pdf"};
    }

    @Override
    /** Export */
    public void export(DocumentData data, File outputFile, DocumentExportConfig config) {
        DocumentExportConfig resolved = config == null
                ? DocumentExportConfig.builder().format("pdf").templateType(DocumentTemplateType.DEFAULT).build()
                : config;
        try {
            DocumentTemplate template = DocumentTemplate.create(resolved.getTemplateType());
            String html = template.renderHtml(data, resolved);

            File htmlFile = File.createTempFile("doc_export_", ".html");
            htmlFile.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(htmlFile)) {
                fos.write(html.getBytes(StandardCharsets.UTF_8));
            }

            boolean success = convertHtmlToPdf(htmlFile, outputFile);
            if (!success) {
                File htmlOutput = new File(outputFile.getParent(),
                        outputFile.getName().replace(".pdf", ".html"));
                try (FileOutputStream fos = new FileOutputStream(htmlOutput)) {
                    fos.write(html.getBytes(StandardCharsets.UTF_8));
                }
                log.warn("PDF 转换失败，已保存 HTML 文件: {}", htmlOutput.getAbsolutePath());
            }
            htmlFile.delete();
        } catch (Exception e) {
            throw new RuntimeException("PDF 导出失败", e);
        }
    }

    /** 转换HtmlToPdf */
    private boolean convertHtmlToPdf(File htmlFile, File pdfFile) {
        String[] commands = {
                "wkhtmltopdf --encoding utf-8 --enable-local-file-access \""
                        + htmlFile.getAbsolutePath() + "\" \"" + pdfFile.getAbsolutePath() + "\"",
                "wkhtmltopdf --encoding utf-8 \""
                        + htmlFile.getAbsolutePath() + "\" \"" + pdfFile.getAbsolutePath() + "\""
        };
        for (String cmd : commands) {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", cmd});
                int exitCode = process.waitFor();
                if (exitCode == 0 && pdfFile.exists()) {
                    log.info("PDF 转换成功: {}", pdfFile.getAbsolutePath());
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "wkhtmltopdf", "--encoding", "utf-8",
                    htmlFile.getAbsolutePath(), pdfFile.getAbsolutePath()
            });
            int exitCode = process.waitFor();
            if (exitCode == 0 && pdfFile.exists()) {
                log.info("PDF 转换成功: {}", pdfFile.getAbsolutePath());
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
