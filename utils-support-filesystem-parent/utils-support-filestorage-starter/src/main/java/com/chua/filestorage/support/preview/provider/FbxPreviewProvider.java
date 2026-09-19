package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * FBX 3D 模型预览提供器。
 * <p>SPI 类型：{@code preview-fbx}。解析 FBX 文件头部信息，提取元数据和网格统计。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param data 数据
 * @return 解析fbx的结果
 * @param content 内容
 * @param ext ext
 * @param mime mime
 */
@Spi("preview-fbx")
public class FbxPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("fbx"); // 支持exts

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        FbxInfo info = parseFbx(content);
        String html = buildHtml(info, content.length);

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
     * 解析Fbx。
     *
     * @param data 数据，不允许为 null
     * @return FbxInfo 对象
     */
    private FbxInfo parseFbx(byte[] data) {
        FbxInfo info = new FbxInfo();

        if (data.length < 27) {
            info.version = "文件太小";
            return info;
        }

        // 检查 FBX 魔数
        String magic = new String(data, 0, 20, StandardCharsets.US_ASCII);
        if (!magic.startsWith("Kaydara FBX")) {
            info.version = "未知格式";
            return info;
        }

        // FBX 版本号在偏移 23-26
        ByteBuffer buf = ByteBuffer.wrap(data, 23, 4);
        info.version = "FBX " + buf.getInt();

        // 简单统计节点数量
        int nodeCount = 0;
        int objectCount = 0;
        for (int i = 0; i < data.length - 13; i++) {
            // 寻找 "Objects\x00" 标记
    /**
     * fbx信息类。
     *
     * @author CH
     * @since 4.0.0
     * @param bytes bytes
     * @return human大小的结果
     */
            if (data[i] == 'O' && data[i + 1] == 'b' && data[i + 2] == 'j' &&
                data[i + 3] == 'e' && data[i + 4] == 'c' && data[i + 5] == 't' &&
                data[i + 6] == 's' && data[i + 7] == 0) {
                objectCount++;
            }
        }

        info.nodeCount = nodeCount;
        info.objectCount = objectCount;

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
    private String buildHtml(FbxInfo info, long fileSize) {
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
        sb.append("<h1>FBX 3D 模型预览</h1>");
        sb.append("<div class=\"meta\">文件大小: ").append(humanSize(fileSize)).append("</div>");
        sb.append("</div>");

        sb.append("<div class=\"info\">");
        sb.append("<div class=\"icon\">3D</div>");
        sb.append("<div class=\"field\"><span class=\"label\">格式</span><span class=\"value\">").append(escapeHtml(info.version)).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">文件大小</span><span class=\"value\">").append(humanSize(fileSize)).append("</span></div>");
        sb.append("<div class=\"field\"><span class=\"label\">说明</span><span class=\"value\">FBX 是 Autodesk 的 3D 模型格式，支持动画、骨骼和材质。请使用专业 3D 软件打开。</span></div>");
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

    private static class FbxInfo {
        String version = "未知"; // 版本
        int nodeCount = 0; // 节点数量
        int objectCount = 0; // 对象数量
    }
}
