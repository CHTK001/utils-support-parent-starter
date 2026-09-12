package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
* Visio 文件预览提供器，跳转至 draw.io 在线查看/编辑 vsdx / vsd / vdx / vssx / vstx 文件。
* <p>SPI 类型：{@code preview-visio}。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("preview-visio")
public class VisioPreviewProvider implements FileStoragePreviewProvider {

    /**
    * 支持的 Visio 扩展名（小写）
     */
    private static final Set<String> SUPPORTED = Set.of("vsdx", "vsd", "vdx", "vssx", "vstx");

    /**
    * @param ext  文件扩展名
    * @param mime MIME 类型（当前忽略）
    * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    /** Preview */
    public PreviewResult preview(byte[] content, String ext, String mime) {
        String b64 = Base64.getEncoder().encodeToString(content);
        String extUpper = ext.toUpperCase(Locale.ENGLISH);
        String html = "<div id=\"app\" style=\"display:flex;flex-direction:column;align-items:center;" +
                "justify-content:center;height:100vh;background:#1a1a2e;color:#e5e7eb;" +
                "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:24px;box-sizing:border-box\">" +
                "<div style=\"font-size:48px;margin-bottom:16px;opacity:0.6\">" +
                "<svg width=\"64\" height=\"64\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\">" +
                "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"3\"/>" +
                "<path d=\"M8 7v10M12 7v10M16 7v10\"/></svg></div>" +
                "<h2 style=\"margin:0 0 8px;font-weight:600;font-size:18px\">" + extUpper + " 文件</h2>" +
                "<p style=\"margin:0 0 24px;color:#9ca3af;font-size:14px;text-align:center\">" +
                "Visio 图表可使用 draw.io 在线查看</p>" +
                "<div style=\"display:flex;gap:12px\">" +
                "<a href=\"https://app.diagrams.net/#Udata:application/octet-stream;base64," + b64 + "\" " +
                "target=\"_blank\" rel=\"noopener\" " +
                "style=\"display:inline-flex;align-items:center;gap:8px;padding:10px 20px;" +
                "background:#2489e6;color:#fff;border-radius:8px;text-decoration:none;font-size:14px;font-weight:500;" +
                "transition:background 0.15s\">" +
                "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">" +
                "<path d=\"M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6\"/>" +
                "<polyline points=\"15 3 21 3 21 9\"/><line x1=\"10\" y1=\"14\" x2=\"21\" y2=\"3\"/></svg>" +
                "在 draw.io 中打开</a>" +
                "</div>" +
                "<p style=\"margin-top:32px;font-size:12px;color:#6b7280\">" +
                "draw.io 支持直接编辑 .vsdx / .vsd 格式文件</p></div>";
        String css = "body{margin:0;padding:0}";
        return PreviewResult.builder().htmlContent(html).embeddedCss(css).build();
    }
}
