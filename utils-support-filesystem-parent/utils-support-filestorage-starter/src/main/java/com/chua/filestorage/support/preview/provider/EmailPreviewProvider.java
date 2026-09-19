package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
 * @param eml eml
 * @return 解析eml的结果
 * @param content 内容
 * @param ext ext
 * @param mime mime
 */
@Spi("preview-email")
public class EmailPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("eml", "msg"); // 支持exts

    /**
     * RFC 2047 编码词正则：=?字符集?B/Q?编码内容?=
     */
    private static final Pattern ENCODED_WORD_PATTERN = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");

    /**
     * 匹配仅包含空白（含折叠换行）的字符串
     */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s*");

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

    /**
     * 解析Eml。
     *
     * @param eml 方法入参 eml
     * @return EmailInfo 对象
     */
    private EmailInfo parseEml(String eml) {
        EmailInfo info = new EmailInfo();

        // 展开 RFC 2822 折叠头：续行以空白开头时合并为单行
        String unfolded = eml.replaceAll("\\r?\\n[ \\t]+", " ");

        // 解析头部
        String[] lines = unfolded.split("\r?\n");
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
                info.subject = decodeHeader(line.substring(8).trim());
            } else if (line.startsWith("From:")) {
                info.from = decodeHeader(line.substring(5).trim());
            } else if (line.startsWith("To:")) {
                info.to = decodeHeader(line.substring(3).trim());
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
    /**
     * email信息类。
     *
     * @author CH
     * @since 4.0.0
     * @param bytes bytes
     * @return human大小的结果
     */
        if (bodyText.length() > 2000) {
            bodyText = bodyText.substring(0, 2000) + "...";
        }

        info.body = bodyText;
        return info;
    /**
     * 构建html。
     * @param info 信息
     * @param fileSize 文件大小
     * @return 构建html的结果
     */
    }

    /**
     * 构建Html。
     *
     * @param info 方法入参 info
     * @param fileSize 文件大小，不允许为 null
     * @return 结果字符串
     */
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
    /**
     * escapehtml。
     * @param text 文本
     * @return escapeHtml的结果
     * @author CH
     * @since 4.0.0
     * @param bytes bytes
     */
    }

    /**
     * 解码 RFC 2047 编码的邮件头。
     * <p>形如 {@code =?UTF-8?B?5rWL6K+V?=} 的编码词会被还原为原文；
     * 相邻编码词之间仅存在折叠空白时直接拼接（RFC 2047 规则）。</p>
     *
     * @param header 原始邮件头
     * @return 解码后的邮件头
     */
    private String decodeHeader(String header) {
        if (header == null || header.indexOf("=?") < 0) {
            return header;
        }

        Matcher matcher = ENCODED_WORD_PATTERN.matcher(header);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int previousWordEnd = -1;

        while (matcher.find()) {
            String between = header.substring(lastEnd, matcher.start());
            // 两个相邻编码词之间的纯空白是折叠产生的，丢弃空白直接拼接
            if (previousWordEnd >= 0 && WHITESPACE_PATTERN.matcher(between).matches()) {
                between = "";
            }
            result.append(between);
            result.append(decodeEncodedWord(matcher.group(1), matcher.group(2), matcher.group(3)));
            previousWordEnd = matcher.end();
            lastEnd = matcher.end();
        }

        if (lastEnd == 0) {
            return header;
        }
        result.append(header.substring(lastEnd));
        return result.toString();
    }

    /**
     * 解码单个 RFC 2047 编码词。
     *
     * @param charsetName 声明的字符集
     * @param encoding B 表示 Base64，Q 表示 Quoted-Printable
     * @param encoded 编码内容
     * @return 解码文本；解码失败时原样返回
     */
    private String decodeEncodedWord(String charsetName, String encoding, String encoded) {
        Charset charset;
        try {
            charset = Charset.forName(charsetName.trim());
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }

        try {
            byte[] bytes;
            if (encoding.equalsIgnoreCase("B")) {
                bytes = Base64.getMimeDecoder().decode(encoded);
            } else {
                bytes = decodeQuotedPrintable(encoded);
            }
            return new String(bytes, charset);
        } catch (Exception e) {
            return "=?" + charsetName + "?" + encoding + "?" + encoded + "?=";
        }
    }

    /**
     * 解码 RFC 2047 Q 编码（下划线表示空格，=XX 表示字节）。
     *
     * @param encoded Q 编码内容
     * @return 原始字节
     */
    private byte[] decodeQuotedPrintable(String encoded) {
        String normalized = encoded.replace('_', ' ');
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '=' && i + 2 < normalized.length()) {
                int high = Character.digit(normalized.charAt(i + 1), 16);
                int low = Character.digit(normalized.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) | low);
                    i += 2;
                    continue;
                }
            }
            out.write(c & 0xFF);
        }

        return out.toByteArray();
    }

    /**
     * escapeHtml。
     *
     * @param text 文本，不允许为 null
     * @return 结果字符串
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * human大小。
     *
     * @param bytes 字节数组，不允许为 null
     * @return 结果字符串
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

    private static class EmailInfo {
        String subject; // 主题
        String from; // 从
        String to; // 转为
        String date; // 日期
        String body = ""; // 主体
    }
}
