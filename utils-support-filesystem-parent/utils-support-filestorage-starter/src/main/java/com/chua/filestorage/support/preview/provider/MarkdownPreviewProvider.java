package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 行内代码：`code`
     */
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");

    /**
     * 加粗：**text**
     */
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    /**
     * 斜体：*text* 或 _text_
     */
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^*\\s][^*]*?)\\*|_([^_\\s][^_]*?)_");

    /**
     * 行内链接：[text](url)
     */
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");

    /**
     * 有序列表前缀：1. 
     */
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\d+\\.\\s+(.*)$");

    /**
     * 占位符前后缀，保护已抽取的行内代码与链接不被二次转换
     */
    private static final String PLACEHOLDER_PREFIX = "\u0000md";
    private static final String PLACEHOLDER_SUFFIX = "\u0000";

    /**
     * 判断是否支持 Markdown 文件预览。
     *
     * @param extension 文件扩展名（如 md）
     * @param mimeType  MIME 类型（如 text/markdown）
     * @return true 表示支持 Markdown 预览
     */
    @Override
    public boolean supports(String extension, String mimeType) {
        return "md".equalsIgnoreCase(extension) || "text/markdown".equals(mimeType);
    }

    /**
     * 将 Markdown 内容渲染为 HTML 并返回预览结果。
     *
     * <p>支持段落、标题、代码块、列表、链接等基础 Markdown 语法。
     * 链接 URL 经 {@link #safeUrl} 过滤危险协议，属性值经 {@link #escapeAttr} 转义。</p>
     *
     * @param content    原始字节
     * @param extension  扩展名
     * @param mimeType   MIME 类型
     * @return 预览结果，含 HTML 内容 + 内嵌 CSS
     * @throws IOException 读取失败
     */
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
                + "ul,ol{padding-left:1.8em;margin:.6em 0}li{margin:.25em 0}"
                + "a{color:#2563eb;text-decoration:none}a:hover{text-decoration:underline}"
                + "img{max-width:100%}";
        return PreviewResult.builder()
                .htmlContent(html)
                .embeddedCss(css)
                .build();
    }

    /**
    * render转为html
    *
    * @param md md
    * @return render转为html的结果
     */
    private static String renderToHtml(String md) {
        StringBuilder sb = new StringBuilder();
        boolean inCodeBlock = false;
        StringBuilder codeBuffer = new StringBuilder();
        String codeLang = "";
        // 当前打开的列表类型：ul / ol，null 表示不在列表中
        String listType = null;

        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (inCodeBlock) {
                    listType = closeList(sb, listType);
                    sb.append("<pre><code").append(codeLang.isEmpty() ? "" : " class=\"language-" + escapeAttr(codeLang) + "\"")
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
            if (t.isEmpty()) {
                listType = closeList(sb, listType);
                sb.append("\n");
                continue;
            }

            if (t.startsWith("### ")) {
                listType = closeList(sb, listType);
                sb.append("<h3>").append(renderInline(t.substring(4))).append("</h3>\n");
            } else if (t.startsWith("## ")) {
                listType = closeList(sb, listType);
                sb.append("<h2>").append(renderInline(t.substring(3))).append("</h2>\n");
            } else if (t.startsWith("# ")) {
                listType = closeList(sb, listType);
                sb.append("<h1>").append(renderInline(t.substring(2))).append("</h1>\n");
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                listType = openList(sb, listType, "ul");
                sb.append("<li>").append(renderInline(t.substring(2))).append("</li>\n");
            } else if (t.startsWith("> ")) {
                listType = closeList(sb, listType);
                sb.append("<blockquote><p>").append(renderInline(t.substring(2))).append("</p></blockquote>\n");
            } else {
                Matcher orderedMatcher = ORDERED_LIST_PATTERN.matcher(t);
                if (orderedMatcher.matches()) {
                    listType = openList(sb, listType, "ol");
                    sb.append("<li>").append(renderInline(orderedMatcher.group(1))).append("</li>\n");
                } else {
                    listType = closeList(sb, listType);
                    sb.append("<p>").append(renderInline(t)).append("</p>\n");
                }
            }
        }

        listType = closeList(sb, listType);
        if (inCodeBlock) {
            sb.append("<pre><code>").append(escapeHtml(codeBuffer.toString())).append("</code></pre>\n");
        }
        return sb.toString();
    }

    /**
    * 切换到指定列表类型，类型变化时关闭旧列表并打开新列表。
    *
    * @param sb HTML 缓冲
    * @param current 当前列表类型（ul/ol/null）
    * @param expected 期望的列表类型
    * @return 切换后的列表类型
    */
    private static String openList(StringBuilder sb, String current, String expected) {
        if (!expected.equals(current)) {
            closeList(sb, current);
            sb.append("<").append(expected).append(">\n");
        }
        return expected;
    }

    /**
    * 关闭当前打开的列表。
    *
    * @param sb HTML 缓冲
    * @param current 当前列表类型（ul/ol/null）
    * @return 恒为 null
    */
    private static String closeList(StringBuilder sb, String current) {
        if (current != null) {
            sb.append("</").append(current).append(">\n");
        }
        return null;
    }

    /**
    * 渲染行内 Markdown 语法：行内代码、加粗、斜体、链接。
    * <p>先转义 HTML，再把代码与链接抽成占位符，避免其内容被加粗/斜体规则二次处理，
    * 最后还原占位符。</p>
    *
    * @param text 原始行文本
    * @return 行内 HTML
    */
    private static String renderInline(String text) {
        String escaped = escapeHtml(text);
        List<String> placeholders = new ArrayList<>();

        Matcher codeMatcher = INLINE_CODE_PATTERN.matcher(escaped);
        StringBuffer codeBuffer = new StringBuffer();
        while (codeMatcher.find()) {
            String replacement = "<code>" + codeMatcher.group(1) + "</code>";
            codeMatcher.appendReplacement(codeBuffer, Matcher.quoteReplacement(toPlaceholder(placeholders, replacement)));
        }
        codeMatcher.appendTail(codeBuffer);

        Matcher linkMatcher = LINK_PATTERN.matcher(codeBuffer.toString());
        StringBuffer linkBuffer = new StringBuffer();
        while (linkMatcher.find()) {
            String url = safeUrl(linkMatcher.group(2));
            String replacement = "<a href=\"" + escapeAttr(url) + "\" rel=\"noopener nofollow\">"
                    + linkMatcher.group(1) + "</a>";
            linkMatcher.appendReplacement(linkBuffer, Matcher.quoteReplacement(toPlaceholder(placeholders, replacement)));
        }
        linkMatcher.appendTail(linkBuffer);

        String result = BOLD_PATTERN.matcher(linkBuffer.toString()).replaceAll("<strong>$1</strong>");
        result = ITALIC_PATTERN.matcher(result).replaceAll("<em>$1$2</em>");

        for (int i = 0; i < placeholders.size(); i++) {
            result = result.replace(PLACEHOLDER_PREFIX + i + PLACEHOLDER_SUFFIX, placeholders.get(i));
        }
        return result;
    }

    /**
    * 生成占位符并暂存真实 HTML 片段。
    *
    * @param placeholders 占位符存储列表
    * @param html 真实 HTML 片段
    * @return 对应占位符
    */
    private static String toPlaceholder(List<String> placeholders, String html) {
        int index = placeholders.size();
        placeholders.add(html);
        return PLACEHOLDER_PREFIX + index + PLACEHOLDER_SUFFIX;
    }

    /**
    * escapehtml
    *
    * @param s s
    * @return escapeHtml的结果
     */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
    * 转义 HTML 属性值中的特殊字符（含引号），防止属性注入 XSS。
    *
    * @param s s
    * @return escapeAttr的结果
     */
    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /**
    * 校验链接 URL，仅允许 安全协议 (http/https/mailto/相对路径)，
    * 阻止 javascript:、data:、vbscript: 等危险协议。
    *
    * @param url url
    * @return 安全URL的结果
     */
    private static String safeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "#";
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("javascript:") || lower.startsWith("data:")
                || lower.startsWith("vbscript:") || lower.startsWith("file:")) {
            return "#";
        }
        return trimmed;
    }
}
