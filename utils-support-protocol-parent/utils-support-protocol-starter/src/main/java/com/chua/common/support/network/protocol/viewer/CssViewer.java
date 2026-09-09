package com.chua.common.support.network.protocol.viewer;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.StringUtils;
import com.chua.common.support.value.Value;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * CSS文件查看器
 * <p>
 * 专门处理CSS文件的查看和语法高亮：
 * 1. CSS语法高亮显示
 * 2. 提供复制、压缩、美化功能
 * 3. 支持CSS格式化和美化
 * 4. 显示CSS规则统计
 * 5. HTML代码压缩优化
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("css")
public class CssViewer implements Viewer {

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "text/css"
    };

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".css"
    };

    @Override
    public boolean supports(String contentType, String extension, ServletRequest request, ServletResponse response) {
        // 检查Content-Type
        if (StringUtils.isNotEmpty(contentType)) {
            String mainType = contentType.split(";")[0].trim().toLowerCase();
            for (String supportedType : SUPPORTED_CONTENT_TYPES) {
                if (supportedType.equals(mainType)) {
                    return true;
                }
            }
        }

        // 检查文件扩展名
        if (StringUtils.isNotEmpty(extension)) {
            String lowerExt = extension.toLowerCase();
            for (String supportedExt : SUPPORTED_EXTENSIONS) {
                if (supportedExt.equals(lowerExt)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void process(ServletRequest request, ServletResponse response) throws Exception {
        // 检查是否为下载模式请求
        Value<Object> mode = request.getAttributeAsValue("mode");
        if (!mode.is("preview")) {
            return;
        }
        byte[] originalBody = response.getBody();
        if (originalBody == null || originalBody.length == 0) {
            log.warn("CSS响应体为空，无法处理");
            return;
        }

        String cssContent = new String(originalBody, StandardCharsets.UTF_8);
        if (log.isDebugEnabled()) {
            log.debug("处理CSS内容，长度: {} bytes", originalBody.length);
        }

        try {
            // 生成高亮HTML
            String highlightHtml = generateCssHighlightHtml(cssContent, request.getPath());

            // 压缩HTML
            String compressedHtml = compressHtml(highlightHtml);

            // 更新响应
            byte[] htmlBytes = compressedHtml.getBytes(StandardCharsets.UTF_8);
            response.setBody(htmlBytes);
            response.setContentType("text/html; charset=utf-8");
            response.addHeader("Content-Length", String.valueOf(htmlBytes.length));

            log.info("成功生成CSS高亮页面，原大小: {} bytes，HTML大小: {} bytes (压缩后)",
                    originalBody.length, htmlBytes.length);

        } catch (Exception e) {
            log.error("处理CSS时发生异常", e);
            handleCssError(response, e);
        }
    }

    /**
     * 生成CSS高亮HTML
     */
    private String generateCssHighlightHtml(String cssContent, String filePath) {
        String escapedCss = escapeHtml(cssContent);
        String fileName = extractFileName(filePath);
        String highlightedCss = highlightCss(escapedCss);
        CssStats stats = analyzeCss(cssContent);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>CSS查看器 - " + escapeHtml(fileName) + "</title>\n" +
                "    <style>\n" +
                "        body {\n" +
                "            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;\n" +
                "            margin: 0;\n" +
                "            padding: 20px;\n" +
                "            background-color: #f5f5f5;\n" +
                "            line-height: 1.6;\n" +
                "        }\n" +
                "        .header {\n" +
                "            background: #fff;\n" +
                "            padding: 15px 20px;\n" +
                "            border-radius: 8px;\n" +
                "            margin-bottom: 20px;\n" +
                "            box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n" +
                "        }\n" +
                "        .header h1 {\n" +
                "            margin: 0;\n" +
                "            color: #333;\n" +
                "            font-size: 24px;\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "        }\n" +
                "        .header .icon {\n" +
                "            margin-right: 10px;\n" +
                "            font-size: 28px;\n" +
                "        }\n" +
                "        .header .info {\n" +
                "            color: #666;\n" +
                "            font-size: 14px;\n" +
                "            margin-top: 8px;\n" +
                "            display: flex;\n" +
                "            gap: 20px;\n" +
                "        }\n" +
                "        .stats {\n" +
                "            background: #e3f2fd;\n" +
                "            padding: 15px 20px;\n" +
                "            border-radius: 8px;\n" +
                "            margin-bottom: 20px;\n" +
                "            border-left: 4px solid #2196f3;\n" +
                "        }\n" +
                "        .stats h3 {\n" +
                "            margin: 0 0 10px 0;\n" +
                "            color: #1976d2;\n" +
                "        }\n" +
                "        .stats-grid {\n" +
                "            display: grid;\n" +
                "            grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));\n" +
                "            gap: 15px;\n" +
                "        }\n" +
                "        .stat-item {\n" +
                "            text-align: center;\n" +
                "        }\n" +
                "        .stat-value {\n" +
                "            font-size: 24px;\n" +
                "            font-weight: bold;\n" +
                "            color: #1976d2;\n" +
                "        }\n" +
                "        .stat-label {\n" +
                "            font-size: 12px;\n" +
                "            color: #666;\n" +
                "        }\n" +
                "        .css-container {\n" +
                "            background: #fff;\n" +
                "            border-radius: 8px;\n" +
                "            overflow: hidden;\n" +
                "            box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n" +
                "        }\n" +
                "        .toolbar {\n" +
                "            background: #f8f9fa;\n" +
                "            padding: 12px 16px;\n" +
                "            border-bottom: 1px solid #e9ecef;\n" +
                "            display: flex;\n" +
                "            gap: 10px;\n" +
                "        }\n" +
                "        .btn {\n" +
                "            background: #007bff;\n" +
                "            color: white;\n" +
                "            border: none;\n" +
                "            padding: 8px 16px;\n" +
                "            border-radius: 4px;\n" +
                "            cursor: pointer;\n" +
                "            font-size: 13px;\n" +
                "            transition: background-color 0.2s;\n" +
                "        }\n" +
                "        .btn:hover {\n" +
                "            background: #0056b3;\n" +
                "        }\n" +
                "        .btn.secondary {\n" +
                "            background: #6c757d;\n" +
                "        }\n" +
                "        .btn.secondary:hover {\n" +
                "            background: #545b62;\n" +
                "        }\n" +
                "        .btn.success {\n" +
                "            background: #28a745;\n" +
                "        }\n" +
                "        .btn.success:hover {\n" +
                "            background: #1e7e34;\n" +
                "        }\n" +
                "        .css-content {\n" +
                "            padding: 20px;\n" +
                "            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;\n" +
                "            font-size: 14px;\n" +
                "            line-height: 1.5;\n" +
                "            max-height: 70vh;\n" +
                "            overflow: auto;\n" +
                "            white-space: pre-wrap;\n" +
                "            word-wrap: break-word;\n" +
                "        }\n" +
                "        .css-selector {\n" +
                "            color: #d73a49;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "        .css-property {\n" +
                "            color: #005cc5;\n" +
                "        }\n" +
                "        .css-value {\n" +
                "            color: #032f62;\n" +
                "        }\n" +
                "        .css-comment {\n" +
                "            color: #6a737d;\n" +
                "            font-style: italic;\n" +
                "        }\n" +
                "        .css-at-rule {\n" +
                "            color: #e36209;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    \n" +
                "    <div class=\"css-container\">\n" +
                "        <div class=\"toolbar\">\n" +
                "            <button class=\"btn\" onclick=\"copyToClipboard()\">📋 复制</button>\n" +
                "            <button class=\"btn secondary\" onclick=\"toggleMinify()\">🗜️ 压缩</button>\n" +
                "            <button class=\"btn success\" onclick=\"formatCss()\">✨ 美化</button>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"css-content\" id=\"css-content\">" + highlightedCss + "</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        const originalCss = " + escapeJs(cssContent) + ";\n" +
                "        let isMinified = false;\n" +
                "        \n" +
                "        function copyToClipboard() {\n" +
                "            navigator.clipboard.writeText(originalCss).then(() => {\n" +
                "                alert('CSS内容已复制到剪贴板');\n" +
                "            }).catch(err => {\n" +
                "                console.error('复制失败:', err);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function toggleMinify() {\n" +
                "            const content = document.getElementById('css-content');\n" +
                "            if (isMinified) {\n" +
                "                content.innerHTML = " + escapeJs(highlightedCss) + ";\n" +
                "                isMinified = false;\n" +
                "            } else {\n" +
                "                const minified = originalCss.replace(/\\s+/g, ' ').replace(/;\\s*}/g, '}').trim();\n" +
                "                content.textContent = minified;\n" +
                "                isMinified = true;\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function formatCss() {\n" +
                "            const content = document.getElementById('css-content');\n" +
                "            const formatted = beautifyCss(originalCss);\n" +
                "            const highlighted = highlightCssText(formatted);\n" +
                "            content.innerHTML = highlighted;\n" +
                "            isMinified = false;\n" +
                "        }\n" +
                "        \n" +
                "        function beautifyCss(css) {\n" +
                "            // 移除多余空白\n" +
                "            css = css.replace(/\\s+/g, ' ').trim();\n" +
                "            \n" +
                "            // 格式化CSS规则\n" +
                "            css = css.replace(/\\{/g, ' {\\n    ')\n" +
                "                     .replace(/;/g, ';\\n    ')\n" +
                "                     .replace(/\\}/g, '\\n}\\n\\n')\n" +
                "                     .replace(/,/g, ',\\n')\n" +
                "                     .replace(/\\n\\s*\\n/g, '\\n')\n" +
                "                     .replace(/\\{\\s*\\n\\s*\\}/g, '{ }')\n" +
                "                     .replace(/;\\s*\\n\\s*\\}/g, ';\\n}')\n" +
                "                     .replace(/\\n\\s+\\n/g, '\\n');\n" +
                "            \n" +
                "            // 处理@规则\n" +
                "            css = css.replace(/@([^{]+)\\{/g, '@$1 {\\n    ');\n" +
                "            \n" +
                "            // 处理注释\n" +
                "            css = css.replace(/\\/\\*([^*]|\\*(?!\\/))*\\*\\//g, function(match) {\n" +
                "                return '\\n' + match + '\\n';\n" +
                "            });\n" +
                "            \n" +
                "            // 清理多余的空行\n" +
                "            css = css.replace(/\\n{3,}/g, '\\n\\n').trim();\n" +
                "            \n" +
                "            return css;\n" +
                "        }\n" +
                "        \n" +
                "        function highlightCssText(css) {\n" +
                "            return css\n" +
                "                .replace(/&/g, '&amp;')\n" +
                "                .replace(/</g, '&lt;')\n" +
                "                .replace(/>/g, '&gt;')\n" +
                "                .replace(/(\\/\\*[\\s\\S]*?\\*\\/)/g, '<span class=\"css-comment\">$1</span>')\n" +
                "                .replace(/(@[a-zA-Z-]+)/g, '<span class=\"css-at-rule\">$1</span>')\n" +
                "                .replace(/([^{}\\n]+)(?=\\s*\\{)/g, '<span class=\"css-selector\">$1</span>')\n" +
                "                .replace(/([a-zA-Z-]+)\\s*:/g, '<span class=\"css-property\">$1</span>:')\n" +
                "                .replace(/:\\s*([^;{}\\n]+)/g, ': <span class=\"css-value\">$1</span>');\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * CSS统计信息类
     */
    private static class CssStats {
        int ruleCount = 0;
        int selectorCount = 0;
        int propertyCount = 0;
        int commentCount = 0;
    }

    /**
     * 分析CSS内容
     */
    private CssStats analyzeCss(String cssContent) {
        CssStats stats = new CssStats();

        // 统计注释
        stats.commentCount = countMatches(cssContent, "/\\*[\\s\\S]*?\\*/");

        // 移除注释后统计其他内容
        String cleanCss = cssContent.replaceAll("/\\*[\\s\\S]*?\\*/", "");

        // 统计CSS规则（大括号对）
        stats.ruleCount = countMatches(cleanCss, "\\{[^}]*\\}");

        // 统计选择器（规则前的部分）
        stats.selectorCount = countMatches(cleanCss, "[^{}]+(?=\\s*\\{)");

        // 统计属性（冒号前的部分）
        stats.propertyCount = countMatches(cleanCss, "[a-zA-Z-]+\\s*:");

        return stats;
    }

    /**
     * 计算匹配次数
     */
    private int countMatches(String text, String regex) {
        try {
            return text.split(regex).length - 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 高亮CSS语法
     */
    private String highlightCss(String css) {
        // CSS语法高亮
        return css
                // 注释
                .replaceAll("(/\\*[\\s\\S]*?\\*/)", "<span class=\"css-comment\">$1</span>")
                // @规则
                .replaceAll("(@[a-zA-Z-]+)", "<span class=\"css-at-rule\">$1</span>")
                // 选择器
                .replaceAll("([^{}]+)(?=\\s*\\{)", "<span class=\"css-selector\">$1</span>")
                // 属性名
                .replaceAll("([a-zA-Z-]+)\\s*:", "<span class=\"css-property\">$1</span>:")
                // 属性值
                .replaceAll(":\\s*([^;{}]+)", ": <span class=\"css-value\">$1</span>");
    }

    /**
     * 压缩HTML代码
     */
    private String compressHtml(String html) {
        if (StringUtils.isEmpty(html)) {
            return html;
        }

        return html
                // 移除HTML注释（保留条件注释）
                .replaceAll("<!--(?!\\[if).*?-->", "")
                // 移除多余的空白字符
                .replaceAll("\\s+", " ")
                // 移除标签间的空白
                .replaceAll(">\\s+<", "><")
                // 移除行首行尾空白
                .replaceAll("^\\s+|\\s+$", "")
                // 移除script和style标签内的空白（保持功能性空白）
                .replaceAll("(<script[^>]*>)\\s+", "$1")
                .replaceAll("\\s+(</script>)", "$1")
                .replaceAll("(<style[^>]*>)\\s+", "$1")
                .replaceAll("\\s+(</style>)", "$1")
                // 移除属性值周围的多余空白
                .replaceAll("=\\s+\"", "=\"")
                .replaceAll("\"\\s+>", "\">")
                // 移除自闭合标签前的空白
                .replaceAll("\\s+/>", "/>")
                .trim();
    }

    /**
     * 提取文件名
     */
    private String extractFileName(String filePath) {
        if (StringUtils.isEmpty(filePath)) {
            return "unknown.css";
        }

        // 移除查询参数
        int queryIndex = filePath.indexOf('?');
        if (queryIndex != -1) {
            filePath = filePath.substring(0, queryIndex);
        }

        // 提取文件名
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < filePath.length() - 1) {
            return filePath.substring(lastSlash + 1);
        }

        return filePath.isEmpty() ? "unknown.css" : filePath;
    }

    /**
     * HTML转义
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * JavaScript转义
     */
    private String escapeJs(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * 处理CSS错误
     */
    private void handleCssError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "CSS处理失败: " + e.getMessage();
            String errorHtml = generateCssErrorHtml(errorMessage);

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);
            response.addHeader("Content-Length", String.valueOf(errorHtml.getBytes(StandardCharsets.UTF_8).length));

            log.error("设置CSS错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理CSS错误时发生异常", ex);
        }
    }

    /**
     * 生成CSS错误页面HTML
     */
    private String generateCssErrorHtml(String errorMessage) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>CSS处理失败</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 50px; background-color: #f5f5f5; }\n" +
                "        .error { color: #d32f2f; background: #ffebee; padding: 20px; border-radius: 8px; border-left: 4px solid #d32f2f; }\n"
                +
                "        .title { font-size: 24px; margin-bottom: 10px; font-weight: bold; }\n" +
                "        .message { font-size: 16px; line-height: 1.5; }\n" +
                "        .icon { font-size: 48px; margin-bottom: 20px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"error\">\n" +
                "        <div class=\"icon\">❌</div>\n" +
                "        <div class=\"title\">CSS处理失败</div>\n" +
                "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public String getViewerName() {
        return "CssViewer";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getDescription() {
        return "CSS文件查看器 - 提供CSS语法高亮、美化、压缩和统计功能";
    }

    @Override
    public String[] getSupportedContentTypes() {
        return SUPPORTED_CONTENT_TYPES;
    }

    @Override
    public String[] getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }
}
