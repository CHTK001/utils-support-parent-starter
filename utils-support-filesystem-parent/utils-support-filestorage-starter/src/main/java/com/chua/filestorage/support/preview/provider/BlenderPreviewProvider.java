package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Blender (.blend) 文件预览提供器。
 * <p>SPI 类型：{@code preview-blender}。解析 BLEND 文件头部，提取版本和元信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param data 数据
 * @return 解析blend的结果
 * @param content 内容
 * @param ext ext
 * @param mime mime
 */
@Spi("preview-blender")
public class BlenderPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("blend"); // 支持exts

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        BlendInfo info = parseBlend(content);
        String html = buildHtml(info, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    private BlendInfo parseBlend(byte[] data) {
        BlendInfo info = new BlendInfo();

        if (data.length < 12) {
            info.version = "文件太小";
            return info;
        }

        // Blender 文件以 "BLENDER" 开头
        String magic = new String(data, 0, 7, StandardCharsets.US_ASCII);
        if (!magic.equals("BLENDER")) {
            info.version = "未知格式";
            return info;
        }

        // 指针大小: '_' = 32位, '-' = 64位
        char ptrChar = (char) data[7];
        info.pointerSize = ptrChar == '-' ? 64 : 32;

        // 字节序: 'v' = 小端, 'V' = 大端
        char endianChar = (char) data[8];
        info.endian = endianChar == 'V' ? "Big" : "Little";

        // 版本号: 3 位数字
    /**
     * blend信息类。
     *
     * @author CH
     * @since 4.0.0
     * @param bytes bytes
     * @return human大小的结果
     */
        info.version = String.format(Locale.ENGLISH, "%c.%c.%c", data[9], data[10], data[11]);

        return info;
    /**
     * 构建html。
     * @param info 信息
     * @param fileSize 文件大小
     * @return 构建html的结果
     */
    }

    private String buildHtml(BlendInfo info, long fileSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        sb.append("<style>");
        sb.append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}");
        sb.append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}");
        sb.append("h1{margin:0 0 8px;font-size:20px;font-weight:600}");
        sb.append(".meta{color:#6b7280;font-size:13px}");
        sb.append(".info{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:700px;margin:0 auto}");
        sb.append(".field{display:flex;padding:10px 0;border-bottom:1px solid #f3f4f6}");
        sb.append(".field:last-child{border-bottom:none}");
        sb.append(".label{color:#6b7280;width:100px;font-size:13px}");
        sb.append(".value{font-weight:500;font-size:14px}");
        sb.append(".icon{font-size:48px;text-align:center;color:#6b7280;margin-bottom:20px}");
        sb.append("</style></head><body>");

        sb.append("<div class=\"header\">");
        sb.append("<h1>Blender 3D 文件预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"info\">");
        sb.append("<div class=\"icon\">3D</div>");
        sb.append("<div class=\"field\"><span class=\"label\">格式</span><span class=\"value\">Blender (.blend)</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">版本</span><span class=\"value\">").append(escapeHtml(info.version)).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">指针大小</span><span class=\"value\">").append(info.pointerSize).append(" 位</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">字节序</span><span class=\"value\">").append(info.endian).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">文件大小</span><span class=\"value\">").append(humanSize(fileSize)).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">说明</span><span class=\"value\">BLEND 是 Blender 的原生 3D 文件格式。请使用 Blender 软件打开。</span></div>");
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

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static class BlendInfo {
        String version = "未知"; // 版本
        int pointerSize = 0; // pointer大小
        String endian = "Unknown"; // endian
    }
}
