package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* RTF 富文本预览提供器。
* <p>SPI 类型：{@code preview-rtf}。提取 RTF 中的纯文本内容显示。</p>
*
* @author CH
* @since 4.0.0.42
* @param rtf rtf
* @return extract文本的结果
* @param content 内容
* @param ext ext
* @param mime mime
 */
@Spi("preview-rtf")
public class RtfPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("rtf"); // 支持exts
    /**
    * 支持。
    * @param ext ext
    * @param mime mime
    * @return 支持的结果
    * @param rtf rtf
    * @param content 内容
     */
    private static final Pattern RTF_GROUP = Pattern.compile("\\\\[a-z]+\\d*\\s?");
    private static final Pattern RTF_SPECIAL = Pattern.compile("\\\\['{}\\\\~_-]");
    private static final Pattern RTF_CONTROL = Pattern.compile("\\\\[a-zA-Z]+\\d*\\s?");
/**
* 支持。
* @param ext ext
* @param mime mime
* @return 支持的结果
 */

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String rtf = new String(content, StandardCharsets.UTF_8);
        String text = extractText(rtf);
        String html = buildHtml(text, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    private String extractText(String rtf) {
        // 移除 RTF 头部
        int docStart = rtf.indexOf("\\pard");
        if (docStart < 0) {
            docStart = rtf.indexOf("\\");
        }
        if (docStart < 0) {
            return rtf;
        }

        String body = rtf.substring(docStart);

        // 移除图片数据
        body = body.replaceAll("\\\\pict[^}]*\\}", "");

        // 移除控制词
        body = RTF_CONTROL.matcher(body).replaceAll("");

        // 处理特殊字符
        body = body.replace("\\par", "\n");
        body = body.replace("\\tab", "\t");
        body = body.replace("\\line", "\n");
        body = body.replace("\\~", " ");
        body = body.replace("\\-", "-");
        body = body.replace("\\_", "_");
        body = body.replace("\\{", "{");
        body = body.replace("\\}", "}");

        // 移除剩余的反斜杠
        body = body.replace("\\", "");

        // 移除花括号
        body = body.replaceAll("[{}]", "");

        // 清理多余空白
        body = body.replaceAll("\\n{3,}", "\n\n");
        body = body.trim();

        return body;
    }

    /**
    * 构建html。
    * @param text 文本
    * @param fileSize 文件大小
    * @return 构建html的结果
     */
    private String buildHtml(String text, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".content{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:700px;margin:0 auto}");
        sb.append(".text{font-size:15px;line-height:1.8;white-space:pre-wrap;color:#374151}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>RTF 富文本预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"content\">");
        sb.append("<div class=\"text\">").append(escapeHtml(text)).append("</div>");
        sb.append("</div></body></html>");

        return sb.toString();
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
