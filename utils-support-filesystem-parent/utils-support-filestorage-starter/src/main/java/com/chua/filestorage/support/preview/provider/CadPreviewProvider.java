package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * CAD 文件预览提供器，支持 dwg、dgn、step、stp、iges、igs 等格式。
 * <p>SPI 类型：{@code preview-cad}。由于 Java 生态缺少成熟的 CAD 解析库，
 * 当前仅显示文件元信息（类型、大小），后续可集成 LibreDWG/Open CASCADE 增强。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-cad")
public class CadPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 CAD 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of(
            "dwg", "dgn", "step", "stp", "iges", "igs", "brep");

    /**
     * 扩展名 → 格式名称映射
     */
    private static final java.util.Map<String, String> FORMAT_NAMES = java.util.Map.of(
            "dwg", "AutoCAD DWG",
            "dgn", "MicroStation DGN",
            "step", "STEP (ISO 10303)",
            "stp", "STEP (ISO 10303)",
            "iges", "IGES",
            "igs", "IGES",
            "brep", "Open CASCADE BREP"
    );

    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        String e = ext.toLowerCase(Locale.ENGLISH);
        String formatName = FORMAT_NAMES.getOrDefault(e, e.toUpperCase());
        String sizeStr = humanSize(content.length);

        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>" +
                "body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;" +
                "display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}" +
                ".card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:32px;max-width:480px;width:100%;" +
                "box-shadow:0 1px 3px rgba(0,0,0,.08)}" +
                ".icon{width:64px;height:64px;background:#eff6ff;border-radius:12px;display:flex;align-items:center;justify-content:center;" +
                "margin:0 auto 20px}" +
                ".icon svg{width:32px;height:32px;color:#3b82f6}" +
                "h2{margin:0 0 8px;font-size:18px;font-weight:600;text-align:center}" +
                ".meta{color:#6b7280;font-size:14px;text-align:center;margin-bottom:20px}" +
                "table{width:100%;border-collapse:collapse}" +
                "td{padding:8px 0;font-size:13px;border-bottom:1px solid #f3f4f6}" +
                "td:first-child{color:#6b7280;width:100px}" +
                "td:last-child{font-weight:500}" +
                ".badge{display:inline-block;background:#dbeafe;color:#1d4ed8;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:500}" +
                ".note{margin-top:16px;padding:12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;" +
                "font-size:12px;color:#92400e}" +
                "</style></head><body>" +
                "<div class=\"card\">" +
                "<div class=\"icon\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">" +
                "<path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/>" +
                "<polyline points=\"14 2 14 8 20 8\"/></svg></div>" +
                "<h2>" + formatName + " 文件预览</h2>" +
                "<div class=\"meta\">CAD 文件需要专业软件打开</div>" +
                "<table>" +
                "<tr><td>格式</td><td><span class=\"badge\">." + e + "</span> " + formatName + "</td></tr>" +
                "<tr><td>大小</td><td>" + sizeStr + "</td></tr>" +
                "<tr><td>扩展名</td><td>." + e + "</td></tr>" +
                "</table>" +
                "<div class=\"note\">" +
                "<strong>提示：</strong>CAD 文件（." + e + "）需要使用专业软件查看：" +
                getSoftwareHint(e) +
                "</div>" +
                "</div></body></html>";

        return PreviewResult.builder()
                .htmlContent(html)
                .build();
    }

    /**
     * 根据扩展名返回推荐软件提示
     */
    private String getSoftwareHint(String ext) {
        return switch (ext) {
            case "dwg" -> "AutoCAD、FreeCAD、LibreCAD、ODA File Converter";
            case "dgn" -> "MicroStation、Bentley View";
            case "step", "stp" -> "FreeCAD、Open CASCADE、CATIA、SolidWorks";
            case "iges", "igs" -> "FreeCAD、Open CASCADE、CATIA";
            case "brep" -> "FreeCAD、Open CASCADE";
            default -> "FreeCAD、AutoCAD";
        };
    }

    /**
     * 人类可读的文件大小
     */
    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
