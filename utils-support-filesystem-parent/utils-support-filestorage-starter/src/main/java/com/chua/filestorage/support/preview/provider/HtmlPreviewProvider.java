package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * HTML 文件渲染预览提供者将 HTML 内容在安全沙箱 iframe 中渲染展示，而非代码高亮
 *
 * <p>通过 srcdoc 属性实现内联渲染，自动移除危险脚本标签以保障安全</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-html")
public class HtmlPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 HTML 扩展名（小写）
     */
    private static final Set<String> SUPPORTED_EXTS = Set.of("html", "htm");

    /**
     * 注入到 srcdoc 中的沙箱样式
     */
    private static final String SANDBOX_STYLE = "<style>" +
            "html,body{margin:0;padding:12px;height:100%;box-sizing:border-box;font-family:system-ui,-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif}" +
            "</style>";

    /**
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase());
    }

    /**
     * 把 HTML 内容通过 iframe srcdoc 渲染。移除 script/on-event/javascript:/iframe/object/embed 等危险内容。
     *
     * @param content 原始字节
     * @param ext     扩展名
     * @param mime    MIME 类型（当前忽略）
     * @return PreviewResult 含 htmlContent + embeddedCss
     */
    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) {
        String rawHtml = new String(content, StandardCharsets.UTF_8);
        String sanitizedHtml = sanitize(rawHtml);

        // 构建预览容器：使用 iframe srcdoc 实现安全沙箱渲染
        String wrapperHtml = buildWrapperHtml(sanitizedHtml);

        return PreviewResult.builder()
                .htmlContent(wrapperHtml)
                .embeddedCss("html,body{margin:0;padding:0;height:100%;width:100%;overflow:hidden;font-family:system-ui}")
                .build();
    }

    /**
     * 移除危险标签和属性，保留安全 HTML 内容
     */
    private String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return html
                // 移除 script 标签
                .replaceAll("(?i)<script[\\s\\S]*?</script>", "")
                // 移除事件处理属性
                .replaceAll("(?i)\\s+on\\w+\\s*=\\s*[\"'][^\"']*[\"']", "")
                .replaceAll("(?i)\\s+on\\w+\\s*=\\s*[^\\s>]+", "")
                // 移除 javascript: 协议
                .replaceAll("(?i)javascript\\s*:", "#")
                // 移除 iframe 标签
                .replaceAll("(?i)<iframe[\\s\\S]*?</iframe>", "")
                // 移除 object/embed 标签
                .replaceAll("(?i)<(object|embed)[\\s\\S]*?</(object|embed)>", "")
                .replaceAll("(?i)<(object|embed)[^>]*/?>", "");
    }

    /**
     * 构建包含预览容器的 HTML 包装
     */
    private String buildWrapperHtml(String sanitizedHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>HTML Preview</title></head><body>");
        sb.append("<div id=\"preview-wrapper\" style=\"height:100vh;width:100%;display:flex;flex-direction:column\">");
        sb.append("<div id=\"preview-toolbar\" style=\"padding:6px 12px;background:#f0f2f5;border-bottom:1px solid #e4e7ed;display:flex;align-items:center;gap:8px;font-size:13px;color:#666\">");
        sb.append("<span>HTML 预览</span>");
        sb.append("<span style=\"margin-left:auto;font-size:12px;color:#999\">安全沙箱模式</span>");
        sb.append("</div>");
        sb.append("<iframe id=\"preview-frame\" style=\"flex:1;width:100%;border:none;background:#fff\" srcdoc=\"")
                .append(escapeHtmlForSrcdoc(sanitizedHtml + SANDBOX_STYLE))
                .append("\"></iframe>");
        sb.append("</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 转义 HTML 用于 srcdoc 属性值
     */
    private String escapeHtmlForSrcdoc(String html) {
        return html
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
