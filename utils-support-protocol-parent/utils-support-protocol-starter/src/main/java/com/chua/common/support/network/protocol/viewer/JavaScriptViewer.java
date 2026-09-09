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
 * JavaScript文件查看器
 * <p>
 * 专门处理JavaScript文件的查看和语法高亮：
 * 1. JavaScript语法高亮显示
 * 2. 提供复制、压缩功能
 * 3. 支持JavaScript格式化
 * 4. 显示JavaScript代码统计
 *
 * @author CH
 * @since 2025/1/8
 */
@Slf4j
@Spi("javascript")
public class JavaScriptViewer implements Viewer {

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "text/javascript",
            "application/javascript",
            "application/x-javascript"
    };

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".js"
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
        // 检查是否为preview模式请求
        Value<Object> mode = request.getAttributeAsValue("mode");
        if (!mode.is("preview")) {
            return;
        }

        byte[] originalBody = response.getBody();
        if (originalBody == null || originalBody.length == 0) {
            log.warn("JavaScript响应体为空，无法处理");
            return;
        }

        String jsContent = new String(originalBody, StandardCharsets.UTF_8);
        if (log.isDebugEnabled()) {
            log.debug("处理JavaScript内容，长度: {} bytes", originalBody.length);
        }

        try {
            // 生成高亮HTML
            String highlightHtml = generateJsHighlightHtml(jsContent, request.getPath());

            // 更新响应
            byte[] htmlBytes = highlightHtml.getBytes(StandardCharsets.UTF_8);
            response.setBody(htmlBytes);
            response.setContentType("text/html; charset=utf-8");
            response.addHeader("Content-Length", String.valueOf(htmlBytes.length));

            log.info("成功生成JavaScript高亮页面，原大小: {} bytes，HTML大小: {} bytes",
                    originalBody.length, htmlBytes.length);

        } catch (Exception e) {
            log.error("处理JavaScript时发生异常", e);
            handleJsError(response, e);
        }
    }

    /**
     * 生成JavaScript高亮HTML
     */
    private String generateJsHighlightHtml(String jsContent, String filePath) {
        String escapedJs = escapeHtml(jsContent);
        String fileName = extractFileName(filePath);
        String highlightedJs = highlightJavaScript(escapedJs);
        JsStats stats = analyzeJavaScript(jsContent);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>JavaScript查看器 - " + escapeHtml(fileName) + "</title>\n" +
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
                "            background: #fff3cd;\n" +
                "            padding: 15px 20px;\n" +
                "            border-radius: 8px;\n" +
                "            margin-bottom: 20px;\n" +
                "            border-left: 4px solid #ffc107;\n" +
                "        }\n" +
                "        .stats h3 {\n" +
                "            margin: 0 0 10px 0;\n" +
                "            color: #856404;\n" +
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
                "            color: #856404;\n" +
                "        }\n" +
                "        .stat-label {\n" +
                "            font-size: 12px;\n" +
                "            color: #666;\n" +
                "        }\n" +
                "        .js-container {\n" +
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
                "            background: #ffc107;\n" +
                "            color: #212529;\n" +
                "            border: none;\n" +
                "            padding: 8px 16px;\n" +
                "            border-radius: 4px;\n" +
                "            cursor: pointer;\n" +
                "            font-size: 13px;\n" +
                "            transition: background-color 0.2s;\n" +
                "        }\n" +
                "        .btn:hover {\n" +
                "            background: #e0a800;\n" +
                "        }\n" +
                "        .btn.secondary {\n" +
                "            background: #6c757d;\n" +
                "            color: white;\n" +
                "        }\n" +
                "        .btn.secondary:hover {\n" +
                "            background: #545b62;\n" +
                "        }\n" +
                "        .js-content {\n" +
                "            padding: 20px;\n" +
                "            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;\n" +
                "            font-size: 14px;\n" +
                "            line-height: 1.5;\n" +
                "            max-height: 70vh;\n" +
                "            overflow: auto;\n" +
                "            white-space: pre-wrap;\n" +
                "            word-wrap: break-word;\n" +
                "        }\n" +
                "        .js-keyword {\n" +
                "            color: #d73a49;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "        .js-string {\n" +
                "            color: #032f62;\n" +
                "        }\n" +
                "        .js-number {\n" +
                "            color: #005cc5;\n" +
                "        }\n" +
                "        .js-comment {\n" +
                "            color: #6a737d;\n" +
                "            font-style: italic;\n" +
                "        }\n" +
                "        .js-function {\n" +
                "            color: #6f42c1;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "        .js-operator {\n" +
                "            color: #e36209;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"header\">\n" +
                "        <h1><span class=\"icon\">⚡</span>JavaScript查看器</h1>\n" +
                "        <div class=\"info\">\n" +
                "            <span>文件: " + escapeHtml(fileName) + "</span>\n" +
                "            <span>大小: " + jsContent.length() + " 字符</span>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"js-container\">\n" +
                "        <div class=\"toolbar\">\n" +
                "            <button class=\"btn\" onclick=\"copyToClipboard()\">📋 复制</button>\n" +
                "            <button class=\"btn secondary\" onclick=\"toggleMinify()\">🗜️ 压缩</button>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"js-content\" id=\"js-content\">" + highlightedJs + "</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        const originalJs = " + escapeJs(jsContent) + ";\n" +
                "        let isMinified = false;\n" +
                "        \n" +
                "        function copyToClipboard() {\n" +
                "            navigator.clipboard.writeText(originalJs).then(() => {\n" +
                "                alert('JavaScript内容已复制到剪贴板');\n" +
                "            }).catch(err => {\n" +
                "                console.error('复制失败:', err);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function toggleMinify() {\n" +
                "            const content = document.getElementById('js-content');\n" +
                "            if (isMinified) {\n" +
                "                content.innerHTML = " + escapeJs(highlightedJs) + ";\n" +
                "                isMinified = false;\n" +
                "            } else {\n" +
                "                const minified = originalJs.replace(/\\s+/g, ' ').replace(/;\\s*}/g, '}').trim();\n" +
                "                content.textContent = minified;\n" +
                "                isMinified = true;\n" +
                "            }\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * JavaScript统计信息类
     */
    private static class JsStats {
        int functionCount = 0;
        int variableCount = 0;
        int lineCount = 0;
        int commentCount = 0;
    }

    /**
     * 分析JavaScript内容
     */
    private JsStats analyzeJavaScript(String jsContent) {
        JsStats stats = new JsStats();

        // 统计行数
        stats.lineCount = jsContent.split("\n").length;

        // 统计注释
        stats.commentCount = countMatches(jsContent, "//.*") + countMatches(jsContent, "/\\*[\\s\\S]*?\\*/");

        // 移除注释后统计其他内容
        String cleanJs = jsContent.replaceAll("//.*", "").replaceAll("/\\*[\\s\\S]*?\\*/", "");

        // 统计函数
        stats.functionCount = countMatches(cleanJs, "function\\s+\\w+") + countMatches(cleanJs, "\\w+\\s*=\\s*function")
                + countMatches(cleanJs, "\\w+\\s*:\\s*function");

        // 统计变量声明
        stats.variableCount = countMatches(cleanJs, "\\b(var|let|const)\\s+\\w+");

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
     * 高亮JavaScript语法
     */
    private String highlightJavaScript(String js) {
        // JavaScript语法高亮
        return js
                // 单行注释
                .replaceAll("(//.*)", "<span class=\"js-comment\">$1</span>")
                // 多行注释
                .replaceAll("(/\\*[\\s\\S]*?\\*/)", "<span class=\"js-comment\">$1</span>")
                // 字符串
                .replaceAll("(['\"`])((?:\\\\.|(?!\\1)[^\\\\])*?)\\1", "<span class=\"js-string\">$1$2$1</span>")
                // 数字
                .replaceAll("\\b(\\d+(?:\\.\\d+)?)\\b", "<span class=\"js-number\">$1</span>")
                // 关键字
                .replaceAll(
                        "\\b(function|var|let|const|if|else|for|while|do|switch|case|break|continue|return|try|catch|finally|throw|new|this|typeof|instanceof|in|of|class|extends|super|static|async|await|yield|import|export|default)\\b",
                        "<span class=\"js-keyword\">$1</span>")
                // 函数名
                .replaceAll("\\b(\\w+)\\s*(?=\\()", "<span class=\"js-function\">$1</span>")
                // 操作符
                .replaceAll("([+\\-*/%=<>!&|^~?:])", "<span class=\"js-operator\">$1</span>");
    }

    /**
     * 提取文件名
     */
    private String extractFileName(String filePath) {
        if (StringUtils.isEmpty(filePath)) {
            return "unknown.js";
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

        return filePath.isEmpty() ? "unknown.js" : filePath;
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
     * 处理JavaScript错误
     */
    private void handleJsError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "JavaScript处理失败: " + e.getMessage();
            String errorHtml = generateJsErrorHtml(errorMessage);

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);
            response.addHeader("Content-Length", String.valueOf(errorHtml.getBytes(StandardCharsets.UTF_8).length));

            log.error("设置JavaScript错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理JavaScript错误时发生异常", ex);
        }
    }

    /**
     * 生成JavaScript错误页面HTML
     */
    private String generateJsErrorHtml(String errorMessage) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>JavaScript处理失败</title>\n" +
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
                "        <div class=\"title\">JavaScript处理失败</div>\n" +
                "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public String getViewerName() {
        return "JavaScriptViewer";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getDescription() {
        return "JavaScript文件查看器 - 提供JavaScript语法高亮和统计功能";
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
