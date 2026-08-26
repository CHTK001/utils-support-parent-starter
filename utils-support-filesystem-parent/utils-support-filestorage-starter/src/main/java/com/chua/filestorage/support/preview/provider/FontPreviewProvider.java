package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 字体文件预览提供器，支持 ttf、otf、woff、woff2 等格式。
 * <p>SPI 类型：{@code preview-font}。使用 CSS @font-face 加载字体并显示预览。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-font")
public class FontPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的字体扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("ttf", "otf", "woff", "woff2", "eot");

    /**
     * 扩展名 → MIME 类型映射
     */
    private static final java.util.Map<String, String> MIME_MAP = java.util.Map.of(
            "ttf", "font/ttf",
            "otf", "font/otf",
            "woff", "font/woff",
            "woff2", "font/woff2",
            "eot", "application/vnd.ms-fontobject"
    );

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String e = ext.toLowerCase(Locale.ENGLISH);
        String mimeType = MIME_MAP.getOrDefault(e, "font/" + e);
        String b64 = java.util.Base64.getEncoder().encodeToString(content);
        String dataUri = "data:" + mimeType + ";base64," + b64;
        String familyName = "PreviewFont_" + System.currentTimeMillis();

        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>" +
                "@font-face{font-family:'" + familyName + "';src:url('" + dataUri + "') format('" + getFormat(e) + "');font-display:swap}" +
                "body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;" +
                "display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}" +
                ".card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:600px;width:100%;" +
                "box-shadow:0 1px 3px rgba(0,0,0,.08)}" +
                ".icon{width:64px;height:64px;background:#faf5ff;border-radius:12px;display:flex;align-items:center;justify-content:center;" +
                "margin:0 auto 20px}" +
                ".icon svg{width:32px;height:32px;color:#9333ea}" +
                "h2{margin:0 0 8px;font-size:18px;font-weight:600;text-align:center}" +
                ".meta{color:#6b7280;font-size:14px;text-align:center;margin-bottom:20px}" +
                ".preview{margin:20px 0;padding:24px;background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;text-align:center}" +
                ".preview-text{font-family:'" + familyName + "';font-size:32px;line-height:1.5;color:#111827}" +
                ".preview-chars{font-family:'" + familyName + "';font-size:24px;letter-spacing:4px;color:#374151;margin-top:12px}" +
                "table{width:100%;border-collapse:collapse}" +
                "td{padding:8px 0;font-size:13px;border-bottom:1px solid #f3f4f6}" +
                "td:first-child{color:#6b7280;width:100px}" +
                "td:last-child{font-weight:500}" +
                ".badge{display:inline-block;background:#f3e8ff;color:#7e22ce;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:500}" +
                "</style></head><body>" +
                "<div class=\"card\">" +
                "<div class=\"icon\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">" +
                "<path d=\"M4 7V4h16v3\"/><path d=\"M9 20h6\"/><path d=\"M12 4v16\"/></svg></div>" +
                "<h2>字体预览</h2>" +
                "<div class=\"meta\">." + e + " 字体文件</div>" +
                "<div class=\"preview\">" +
                "<div class=\"preview-text\">The quick brown fox jumps over the lazy dog</div>" +
                "<div class=\"preview-chars\">ABCDEFGHijklm0123456789</div>" +
                "</div>" +
                "<table>" +
                "<tr><td>格式</td><td><span class=\"badge\">." + e + "</span> " + getFormatName(e) + "</td></tr>" +
                "<tr><td>大小</td><td>" + humanSize(content.length) + "</td></tr>" +
                "</table>" +
                "</div></body></html>";

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    private String getFormat(String ext) {
        return switch (ext) {
            case "ttf" -> "truetype";
            case "otf" -> "opentype";
            case "woff" -> "woff";
            case "woff2" -> "woff2";
            case "eot" -> "embedded-opentype";
            default -> ext;
        };
    }

    private String getFormatName(String ext) {
        return switch (ext) {
            case "ttf" -> "TrueType Font";
            case "otf" -> "OpenType Font";
            case "woff" -> "Web Open Font Format";
            case "woff2" -> "Web Open Font Format 2";
            case "eot" -> "Embedded OpenType";
            default -> ext.toUpperCase();
        };
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
