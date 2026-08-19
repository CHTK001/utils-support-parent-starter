package com.chua.pdf.support.file.impl;

import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.file.template.TemplateFileSystem;
import com.chua.common.support.spi.ServiceProvider;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF 文件写入构建器。
 *
 * <p>基于 PDFBox 实现简单文本内容的 PDF 文件生成。
 * 支持延迟写入（多次 write + finish）和实时写入（writeAndFlush），
 * 设置 {@link #withTemplate(File)} 后只走模板模式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PdfWriteBuilder extends WriteBuilder {

    /**
     * PDF 文档标题
     */
    private String title;

    /**
     * PDF 文档作者
     */
    private String author;

    /**
     * 模板文件流
     */
    private InputStream templateStream;

    /**
     * 字体大小，默认 12
     */
    private float fontSize = 12;

    /**
     * 左边距，默认 50
     */
    private float leftMargin = 50;

    /**
     * 顶部起始位置，默认 750
     */
    private float topMargin = 750;

    /**
     * 行间距，默认 16
     */
    private float lineSpacing = 16;

    public PdfWriteBuilder(File file) {
        super(file);
    }

    /**
     * 设置 PDF 标题。
     *
     * @param title 文档标题
     * @return 当前构建器
     */
    public PdfWriteBuilder title(String title) {
        this.title = title;
        return this;
    }

    /**
     * 设置作者。
     *
     * @param author 文档作者
     * @return 当前构建器
     */
    public PdfWriteBuilder author(String author) {
        this.author = author;
        return this;
    }

    /**
     * 设置字体大小。
     *
     * @param size 字体大小
     * @return 当前构建器
     */
    public PdfWriteBuilder fontSize(float size) {
        this.fontSize = size;
        return this;
    }

    /**
     * 设置左边距。
     *
     * @param margin 左边距
     * @return 当前构建器
     */
    public PdfWriteBuilder leftMargin(float margin) {
        this.leftMargin = margin;
        return this;
    }

    /**
     * 设置顶部起始位置。
     *
     * @param margin 顶部起始位置
     * @return 当前构建器
     */
    public PdfWriteBuilder topMargin(float margin) {
        this.topMargin = margin;
        return this;
    }

    /**
     * 设置行间距。
     *
     * @param spacing 行间距
     * @return 当前构建器
     */
    public PdfWriteBuilder lineSpacing(float spacing) {
        this.lineSpacing = spacing;
        return this;
    }

    /**
     * 设置模板文件流（设置后只走模板）。
     *
     * @param stream 模板文件流
     * @return 当前构建器
     */
    public PdfWriteBuilder withTemplate(InputStream stream) {
        this.templateStream = stream;
        return this;
    }

    /**
     * 将文本行加入延迟写入队列。
     *
     * @param lines 文本行列表
     */
    public PdfWriteBuilder write(List<String> lines) {
        pending.add(lines);
        return this;
    }

    @Override
    public PdfWriteBuilder write(Object data) {
        pending.add(data);
        return this;
    }

    /**
     * 将 Map 数据加入延迟写入队列。
     *
     * @param rows Map 数据列表
     */
    public PdfWriteBuilder writeMap(List<Map<String, Object>> rows) {
        pending.add(rows);
        return this;
    }

    /**
     * 实时写入文本行。
     *
     * @param lines 文本行列表
     */
    public void writeAndFlush(List<String> lines) {
        callback.onStart();
        callback.onBeginWrite();
        if (templateFile != null || templateStream != null) {
            resolveTemplate();
            callback.onComplete(true);
            return;
        }
        doWriteText(lines);
        callback.onComplete(true);
    }

    /**
     * 实时写入 Map 数据。
     *
     * @param rows Map 数据列表
     */
    public void writeAndFlushMap(List<Map<String, Object>> rows) {
        callback.onStart();
        callback.onBeginWrite();
        if (templateFile != null || templateStream != null) {
            resolveTemplate();
            callback.onComplete(true);
            return;
        }
        doWriteMap(rows);
        callback.onComplete(true);
    }

    @Override
    public void finish() {
        callback.onStart();
        callback.onBeginWrite();
        if (templateFile != null || templateStream != null) {
            resolveTemplate();
            callback.onComplete(true);
            return;
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            setMetadata(doc);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
                cs.newLineAtOffset(leftMargin, topMargin);
                int written = 0;
                for (Object entry : pending) {
                    if (entry instanceof List) {
                        for (Object item : (List<?>) entry) {
                            if (item instanceof String) {
                                cs.showText((String) item);
                                cs.newLineAtOffset(0, -lineSpacing);
                                written += ((String) item).getBytes().length;
                            } else if (item instanceof Map) {
                                Map<String, Object> row = (Map<String, Object>) item;
                                StringBuilder sb = new StringBuilder();
                                for (Map.Entry<String, Object> e : row.entrySet()) {
                                    String key = e.getKey();
                                    if (columnMapping != null) {
                                        key = columnMapping.getOrDefault(key, key);
                                    }
                                    sb.append(key).append(": ").append(e.getValue()).append("  ");
                                }
                                cs.showText(sb.toString());
                                cs.newLineAtOffset(0, -lineSpacing);
                                written += sb.toString().getBytes().length;
                            }
                        }
                    }
                }
                cs.endText();
                callback.onProgress(written, written);
            }
            doc.save(file);
            callback.onComplete(true);
        } catch (IOException e) {
            callback.onComplete(false);
            throw new RuntimeException("PDF 写入失败", e);
        }
    }

    private void setMetadata(PDDocument doc) {
        PDDocumentInformation info = new PDDocumentInformation();
        if (title != null) {
            info.setTitle(title);
        }
        if (author != null) {
            info.setAuthor(author);
        }
        doc.setDocumentInformation(info);
    }

    private void doWriteText(List<String> lines) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            setMetadata(doc);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
                cs.newLineAtOffset(leftMargin, topMargin);
                int written = 0;
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -lineSpacing);
                    written += line.getBytes().length;
                }
                cs.endText();
                callback.onProgress(written, written);
            }
            doc.save(file);
        } catch (IOException e) {
            throw new RuntimeException("PDF 写入失败", e);
        }
    }

    private void doWriteMap(List<Map<String, Object>> rows) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            setMetadata(doc);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
                cs.newLineAtOffset(leftMargin, topMargin);
                int written = 0;
                for (Map<String, Object> row : rows) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        String key = entry.getKey();
                        if (columnMapping != null) {
                            key = columnMapping.getOrDefault(key, key);
                        }
                        sb.append(key).append(": ").append(entry.getValue()).append("  ");
                    }
                    cs.showText(sb.toString());
                    cs.newLineAtOffset(0, -lineSpacing);
                    written += sb.toString().getBytes().length;
                }
                cs.endText();
                callback.onProgress(written, written);
            }
            doc.save(file);
        } catch (IOException e) {
            throw new RuntimeException("PDF 写入失败", e);
        }
    }

    private void resolveTemplate() {
        try {
            TemplateFileSystem engine = ServiceProvider.of(TemplateFileSystem.class).getExtension("txt");
            if (engine == null) {
                throw new IllegalStateException("未找到 FileTemplateSystem 实现");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream tis = templateStream != null ? templateStream : new FileInputStream(templateFile)) {
                engine.resolve(tis, bos, templateData);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bos.toByteArray());
            }
        } catch (IOException e) {
            throw new RuntimeException("PDF 模板写入失败", e);
        }
    }
}
