package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Markdown 预览提供者：将 MD 转为 HTML。
 *
 * <p>不依赖外部库，仅做基础渲染（段落、标题、代码块、列表、链接）。
 * 若项目中含 commonmark 等库，替换为更强实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-markdown")
public class MarkdownPreviewProvider implements FileStoragePreviewProvider {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "md".equalsIgnoreCase(extension) || "text/markdown".equals(mimeType);
    }

    @Override
    public PreviewResult preview(byte[] content, String extension, String mimeType) throws IOException {
        String md = new String(content, StandardCharsets.UTF_8);
        String html = renderToHtml(md);
        String css = "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "line-height:1.6;padding:2em;max-width:860px;margin:0 auto;color:#333}"
                + "pre{background:#f5f5f5;padding:1em;border-radius:6px;overflow-x:auto}"
                + "code{background:#f0f0f0;padding:.15em .3em;border-radius:3px;font-size:.9em}"
                + "pre code{background:none;padding:0}"
                + "table{border-collapse:collapse;width:100%}"
                + "th,td{border:1px solid #ddd;padding:8px;text-align:left}"
                + "th{background:#f8f8f8}"
                + "blockquote{border-left:4px solid #ddd;margin:0;padding:0 1em;color:#666}"
                + "img{max-width:100%}";
        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss(css)
                .build();
    }

    private static String renderToHtml(String md) {
        StringBuilder sb = new StringBuilder();
        boolean inCodeBlock = false;
        StringBuilder codeBuffer = new StringBuilder();
        String codeLang = "";
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (inCodeBlock) {
                    sb.append("<pre><code").append(codeLang.isEmpty() ? "" : " class=\"language-" + escapeHtml(codeLang) + "\"")
                            .append(">").append(escapeHtml(codeBuffer.toString())).append("</code></pre>\n");
                    codeBuffer.setLength(0);
                    codeLang = "";
                    inCodeBlock = false;
                } else {
                    inCodeBlock = true;
                    codeLang = t.length() > 3 ? t.substring(3).trim() : "";
                }
                continue;
            }
            if (inCodeBlock) {
                codeBuffer.append(line).append("\n");
                continue;
            }
            if (t.isEmpty()) { sb.append("\n"); continue; }

            if (t.startsWith("### ")) sb.append("<h3>").append(escapeHtml(t.substring(4))).append("</h3>\n");
            else if (t.startsWith("## ")) sb.append("<h2>").append(escapeHtml(t.substring(3))).append("</h2>\n");
            else if (t.startsWith("# ")) sb.append("<h1>").append(escapeHtml(t.substring(2))).append("</h1>\n");
            else if (t.startsWith("- ") || t.startsWith("* ")) sb.append("<li>").append(escapeHtml(t.substring(2))).append("</li>\n");
            else if (t.startsWith("> ")) sb.append("<blockquote><p>").append(escapeHtml(t.substring(2))).append("</p></blockquote>\n");
            else if (t.matches("^\\d+\\.\\s.*")) {
                String[] parts = t.split("\\.\\s", 2);
                sb.append("<li>").append(escapeHtml(parts.length > 1 ? parts[1] : "")).append("</li>\n");
            } else if (t.startsWith("[") && t.contains("](") && t.endsWith(")")) {
                int c1 = t.indexOf(']');
                int c2 = t.indexOf('(', c1);
                String linkText = t.substring(1, c1);
                String linkUrl = t.substring(c2 + 1, t.length() - 1);
                sb.append("<p><a href=\"").append(escapeHtml(linkUrl)).append("\">")
                        .append(escapeHtml(linkText)).append("</a></p>\n");
            } else {
                sb.append("<p>").append(escapeHtml(t)).append("</p>\n");
            }
        }
        if (inCodeBlock) {
            sb.append("<pre><code>").append(escapeHtml(codeBuffer.toString())).append("</code></pre>\n");
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
