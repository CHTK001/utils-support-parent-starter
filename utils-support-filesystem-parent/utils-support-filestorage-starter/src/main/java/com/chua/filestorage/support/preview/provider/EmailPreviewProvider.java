package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EML 邮件预览提供器。
 * <p>SPI 类型：{@code preview-email}。解析 EML 文件的头部信息和正文。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-email")
public class EmailPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("eml", "msg");

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String eml = new String(content, StandardCharsets.UTF_8);
        EmailInfo info = parseEml(eml);
        String html = buildHtml(info, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    private EmailInfo parseEml(String eml) {
        EmailInfo info = new EmailInfo();

        // 解析头部
        String[] lines = eml.split("\r?\n");
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        boolean isMultipart = false;
        String boundary = null;

        for (String line : lines) {
            if (inBody) {
                if (line.startsWith("--" + (boundary != null ? boundary : ""))) {
                    continue;
                }
                body.append(line).append("\n");
                continue;
            }

            if (line.isEmpty()) {
                inBody = true;
                continue;
            }

            if (line.startsWith("Subject:")) {
                info.subject = line.substring(8).trim();
            } else if (line.startsWith("From:")) {
                info.from = line.substring(5).trim();
            } else if (line.startsWith("To:")) {
                info.to = line.substring(3).trim();
            } else if (line.startsWith("Date:")) {
                info.date = line.substring(5).trim();
            } else if (line.startsWith("Content-Type:")) {
                if (line.toLowerCase(Locale.ROOT).contains("multipart")) {
                    isMultipart = true;
                    Matcher m = Pattern.compile("boundary=\"?([^\";]+)\"?").matcher(line);
                    if (m.find()) {
                        boundary = m.group(1);
                    }
                }
            }
        }

        // 清理正文
        String bodyText = body.toString().trim();
        // 移除 MIME 边界
        if (boundary != null) {
            bodyText = bodyText.replaceAll("--" + Pattern.quote(boundary) + ".*", "");
        }
        // 移除 HTML 标签
        bodyText = bodyText.replaceAll("<[^>]+>", "");
        // 截断
        if (bodyText.length() > 2000) {
            bodyText = bodyText.substring(0, 2000) + "...";
        }

        info.body = bodyText;
        return info;
    }

    private String buildHtml(EmailInfo info, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".email{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:700px;margin:0 auto}");
        sb.append(".field{display:flex;padding:8px 0;border-bottom:1px solid #f3f4f6}");
        sb.append(".label{color:#6b7280;width:80px;font-size:13px}");
        sb.append(".value{font-weight:500;font-size:14px}");
        sb.append(".body{margin-top:20px;padding-top:20px;border-top:1px solid #e5e7eb}");
        sb.append(".body-text{font-size:14px;line-height:1.7;color:#374151;white-space:pre-wrap}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>邮件预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"email\">");

        if (info.subject != null) {
            sb.append("<div class=\"field\"><span class=\"label\">主题</span><span class=\"value\">").append(escapeHtml(info.subject)).append("</span></div>");
        }
        if (info.from != null) {
            sb.append("<div class=\"field\"><span class=\"label\">发件人</span><span class=\"value\">").append(escapeHtml(info.from)).append("</span></div>");
        }
        if (info.to != null) {
            sb.append("<div class=\"field\"><span class=\"label\">收件人</span><span class=\"value\">").append(escapeHtml(info.to)).append("</span></div>");
        }
        if (info.date != null) {
            sb.append("<div class=\"field\"><span class=\"label\">日期</span><span class=\"value\">").append(escapeHtml(info.date)).append("</span></div>");
        }

        if (!info.body.isEmpty()) {
            sb.append("<div class=\"body\">");
            sb.append("<div class=\"body-text\">").append(escapeHtml(info.body)).append("</div>");
            sb.append("</div>");
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static class EmailInfo {
        String subject;
        String from;
        String to;
        String date;
        String body = "";
    }
}
