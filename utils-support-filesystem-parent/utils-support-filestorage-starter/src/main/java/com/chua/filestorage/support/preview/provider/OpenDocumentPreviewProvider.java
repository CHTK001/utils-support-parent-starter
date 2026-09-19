package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.util.Base64;
import java.util.Set;

/**
 * 打开文档 格式预览提供者支持 odt (文本), ods (表格), odp (演示文稿)
 *
 * <p>实现思路：将 OpenDocument 文件转换为 PDF 后使用浏览器 PDF 预览能力展示</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-opendocument")
public class OpenDocumentPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 打开文档 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("odt", "ods", "odp");

    /** Base64 内嵌 打开文档 预览允许的最大字节数（约 20 MB） */
    private static final long MAX_OD_PREVIEW_BYTES = 20L * 1024 * 1024;

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
    /** Preview */
    public PreviewResult preview(byte[] content, String ext, String mime) {
        if (content.length > MAX_OD_PREVIEW_BYTES) {
            return PreviewResult.builder()
                    .htmlContent("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"></head>"
                            + "<body style=\"display:flex;justify-content:center;align-items:center;"
                            + "min-height:100vh;font-family:sans-serif;color:#666\">"
                            + "<div>打开文档文件过大（超过 20 MB），暂不支持内嵌预览</div>"
                            + "</body></html>")
                    .build();
        }
        String b64 = Base64.getEncoder().encodeToString(content);
        String type = ext.toLowerCase();

 // 构建 打开文档 转 PDF 的提示页面
 // 实际转换建议通过后端 libre办公室 服务或前端插件完成
        String html = buildPreviewHtml(b64, type);

        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss("html,body{margin:0;padding:0;height:100%;width:100%;overflow:auto;font-family:system-ui}")
                .build();
    }

    /**
     * 构建previewhtml
     *
     * @param b64 b64
     * @param type 类型
     * @return 构建previewhtml的结果
     */
    private String buildPreviewHtml(String b64, String type) {
        String fileName = "uploaded." + type;
        String mimeType = getMimeType(type);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>OpenDocument Preview</title></head><body>");
        sb.append("<div id=\"app\" style=\"height:100vh;width:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;background:#f5f5f5\">");
        sb.append("<div style=\"text-align:center;padding:40px;background:#fff;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,0.1);max-width:600px;width:90%\">");
        sb.append("<svg style=\"width:64px;height:64px;margin-bottom:16px\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#409EFF\" stroke-width=\"2\">");
        sb.append("<path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/>");
        sb.append("<polyline points=\"14 2 14 8 20 8\"/>");
        sb.append("<line x1=\"16\" y1=\"13\" x2=\"8\" y2=\"13\"/>");
        sb.append("<line x1=\"16\" y1=\"17\" x2=\"8\" y2=\"17\"/>");
        sb.append("<polyline points=\"10 9 9 9 8 9\"/>");
        sb.append("</svg>");
        sb.append("<h2 style=\"color:#333;margin:16px 0\">OpenDocument 文件预览</h2>");
        sb.append("<p style=\"color:#666;margin:8px 0\">文件类型: ").append(type.toUpperCase()).append("</p>");
        sb.append("<p style=\"color:#999;font-size:14px;margin:16px 0\">");
        sb.append("OpenDocument 格式需要转换为 PDF 后预览。<br/>");
        sb.append("请配置 LibreOffice 转换服务或使用前端插件进行预览。");
        sb.append("</p>");
        sb.append("<div style=\"margin-top:24px;padding:16px;background:#f0f9ff;border-radius:8px;text-align:left\">");
        sb.append("<p style=\"margin:4px 0;color:#666;font-size:14px\"><strong>建议方案：</strong></p>");
        sb.append("<ul style=\"color:#666;font-size:14px;padding-left:20px\">");
        sb.append("<li>配置 LibreOffice Online / Collabora Online 服务</li>");
        sb.append("<li>使用 OnlyOffice / Univer 转换服务</li>");
        sb.append("<li>前端集成 ODf 预览插件</li>");
        sb.append("</ul>");
        sb.append("</div>");
        sb.append("</div>");
        sb.append("</div>");
        sb.append("</body></html>");

        return sb.toString();
    }

    /**
     * 获取mime类型
     *
     * @param type 类型
     * @return 获取mime类型的结果
     */
    private String getMimeType(String type) {
        return switch (type) {
            case "odt" -> "application/vnd.oasis.opendocument.text";
            case "ods" -> "application/vnd.oasis.opendocument.spreadsheet";
            case "odp" -> "application/vnd.oasis.opendocument.presentation";
            default -> "application/octet-stream";
        };
    }
}
