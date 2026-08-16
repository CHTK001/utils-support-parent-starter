package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * SVG 矢量图形增强预览提供者支持原始 SVG 渲染、缩放、居中展示，可用于后续集成水印等增强能力
 *
 * <p>虽然浏览器原生支持 SVG，但通过 SPI 提供者可以添加：
 * 自适应缩放、居中展示、工具栏、水印叠加等能力</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-svg")
public class SvgPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 SVG 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("svg");

    /**
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase());
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) {
        String svgContent = new String(content, StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(content);

        String html = buildPreviewHtml(svgContent, b64);

        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss("html,body{margin:0;padding:0;height:100%;width:100%;overflow:hidden;font-family:system-ui}" +
                        "#svg-container{overflow:auto;background:#fafafa}" +
                        "#svg-container img{display:block;transition:transform .2s ease}")
                .build();
    }

    private String buildPreviewHtml(String svgContent, String b64) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>SVG Preview</title></head><body>");
        sb.append("<div id=\"app\" style=\"height:100vh;width:100%;display:flex;flex-direction:column\">");

        // 工具栏
        sb.append("<div id=\"toolbar\" style=\"padding:6px 12px;background:#f0f2f5;border-bottom:1px solid #e4e7ed;" +
                "display:flex;align-items:center;gap:10px;font-size:13px;color:#666;flex-shrink:0\">");
        sb.append("<span>SVG 预览</span>");
        sb.append("<span style=\"margin-left:auto;font-size:12px;color:#999\">矢量图形</span>");
        sb.append("<button onclick=\"zoomIn()\" style=\"padding:2px 10px;border:1px solid #dcdfe6;background:#fff;" +
                "border-radius:4px;cursor:pointer;font-size:14px\">+</button>");
        sb.append("<button onclick=\"zoomOut()\" style=\"padding:2px 10px;border:1px solid #dcdfe6;background:#fff;" +
                "border-radius:4px;cursor:pointer;font-size:14px\">−</button>");
        sb.append("<button onclick=\"zoomReset()\" style=\"padding:2px 10px;border:1px solid #dcdfe6;background:#fff;" +
                "border-radius:4px;cursor:pointer;font-size:12px\">重置</button>");
        sb.append("<span id=\"zoom-level\" style=\"font-size:12px;color:#999;min-width:40px\">100%</span>");
        sb.append("</div>");

        // SVG 容器
        sb.append("<div id=\"svg-container\" style=\"flex:1;overflow:auto;display:flex;align-items:center;justify-content:center;background:#fafafa\">");
        sb.append("<div id=\"svg-wrapper\" style=\"padding:40px;transform-origin:center center\">");
        sb.append("<img src=\"data:image/svg+xml;base64,").append(b64).append("\" " +
                "style=\"max-width:none;box-shadow:0 2px 8px rgba(0,0,0,0.08);border-radius:4px;\" " +
                "alt=\"SVG Preview\">");
        sb.append("</div>");
        sb.append("</div>");

        // 缩放脚本
        sb.append("<script>")
                .append("var zoom=1;")
                .append("function updateZoom(z){zoom=Math.max(0.1,Math.min(5,z));")
                .append("document.getElementById('svg-wrapper').style.transform='scale('+zoom+')';")
                .append("document.getElementById('zoom-level').textContent=Math.round(zoom*100)+'%';}")
                .append("function zoomIn(){updateZoom(zoom+0.25);}")
                .append("function zoomOut(){updateZoom(zoom-0.25);}")
                .append("function zoomReset(){updateZoom(1);}")
                .append("</script>");

        sb.append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }
}
