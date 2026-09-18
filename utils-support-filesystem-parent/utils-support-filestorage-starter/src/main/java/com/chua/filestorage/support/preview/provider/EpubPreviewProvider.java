package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
* EPUB 电子书预览提供器。
* <p>SPI 类型：{@code preview-epub}。解析 EPUB ZIP 中的 XHTML 内容，提取文本显示。</p>
*
* @author CH
* @since 4.0.0.42
* @param html HTML
* @return extract文本从html的结果
* @param content 内容
* @param ext ext
* @param mime mime
 */
@Spi("preview-epub")
public class EpubPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("epub"); // 支持exts

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        List<String> chapters = extractChapters(content);
        String html = buildHtml(chapters, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    /**
    * extractchapters。
    * @param epubBytes epubbytes
    * @return extractChapters的结果
    * @param html html
    */
    }

    /**
     * extractChapters。
     *
     * @param epubBytes epub字节数组，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws IOException 当执行过程不满足前置条件时
     */
    private List<String> extractChapters(byte[] epubBytes) throws IOException {
        List<String> chapters = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(epubBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                    String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    String text = extractTextFromHtml(content);
                    if (!text.isBlank()) {
                        chapters.add(text);
                    }
                }
            }
        }
        return chapters;
    }

    /**
     * extract文本来自Html。
     *
     * @param html 方法入参 html
     * @return 结果字符串
     */
    private String extractTextFromHtml(String html) {
        // 简单提取 HTML 中的文本内容
        StringBuilder sb = new StringBuilder();
        boolean inTag = false;
        boolean inScript = false;

        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);

            if (c == '<') {
                inTag = true;
                String tag = html.substring(i, Math.min(i + 7, html.length())).toLowerCase(Locale.ROOT);
                if (tag.startsWith("<script")) {
                    inScript = true;
                }
                continue;
            }

            if (c == '>' && inTag) {
                inTag = false;
                continue;
            }

            if (!inTag && !inScript) {
                if (c == '&' && html.substring(i, Math.min(i + 5, html.length())).startsWith("&amp;")) {
                    sb.append('&');
                    i += 4;
                } else if (c == '&' && html.substring(i, Math.min(i + 4, html.length())).startsWith("&lt;")) {
                    sb.append('<');
                    i += 3;
                } else if (c == '&' && html.substring(i, Math.min(i + 4, html.length())).startsWith("&gt;")) {
                    sb.append('>');
                    i += 3;
                } else {
                    sb.append(c);
                }
            }

            if (inScript && c == '>' && html.substring(Math.max(0, i - 7), i + 1).toLowerCase(Locale.ROOT).contains("</script")) {
                inScript = false;
            }
        }

        return sb.toString().trim();
    }

    /**
    * 构建html。
    * @param chapters chapters
    * @param fileSize 文件大小
    * @return 构建html的结果
    */
    private String buildHtml(List<String> chapters, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:Georgia,'Times New Roman',serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".book{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:700px;margin:0 auto}");
        sb.append(".chapter{margin-bottom:24px;padding-bottom:24px;border-bottom:1px solid #f3f4f6}");
        sb.append(".chapter:last-child{border-bottom:none}");
        sb.append(".chapter-title{font-size:16px;font-weight:600;margin-bottom:12px;color:#111827}");
        sb.append(".chapter-text{font-size:15px;line-height:1.8;color:#374151;white-space:pre-wrap}");
        sb.append(".empty{color:#6b7280;text-align:center;padding:40px}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>EPUB 电子书预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append(" · 章节: ").append(chapters.size()).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"book\">");

        if (chapters.isEmpty()) {
            sb.append("<div class=\"empty\">无法提取电子书内容</div>");
        } else {
            for (int i = 0; i < chapters.size() && i < 20; i++) {
                sb.append("<div class=\"chapter\">");
                sb.append("<div class=\"chapter-title\">第 ").append(i + 1).append(" 章</div>");
                sb.append("<div class=\"chapter-text\">").append(escapeHtml(truncate(chapters.get(i), 500))).append("</div>");
                sb.append("</div>");
            }
            if (chapters.size() > 20) {
                sb.append("<div class=\"empty\">... 还有 ").append(chapters.size() - 20).append(" 章未显示</div>");
            }
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    /**
    * truncate。
    * @param text 文本
    * @param maxLen 最大len
    * @return truncate的结果
    */
    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    /**
    * escapehtml。
    * @param text 文本
    * @return escapeHtml的结果
    */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
    * human大小。
    * @param bytes bytes
    * @return human大小的结果
    */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
