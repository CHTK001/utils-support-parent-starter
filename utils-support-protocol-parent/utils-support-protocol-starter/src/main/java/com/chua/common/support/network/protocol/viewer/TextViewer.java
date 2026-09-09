package com.chua.common.support.network.protocol.viewer;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文本文件查看器
 * <p>
 * 专门处理纯文本文件的查看：
 * 1. 支持多种文本格式
 * 2. 提供语法高亮（基于文件扩展名）
 * 3. 显示行号和文件统计
 * 4. 支持文本搜索和复制功能
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("text")
public class TextViewer implements Viewer {

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "text/plain",
            "text/html",
            "text/xml",
            "application/xml",
            "text/javascript",
            "application/javascript",
            "text/x-java-source",
            "text/x-python",
            "text/x-db"
    };

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".txt", ".log", ".md", ".html", ".htm", ".xml", ".js", ".java",
            ".py", ".db", ".sh", ".bat", ".yml", ".yaml", ".properties",
            ".ini", ".conf", ".cfg"
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
        byte[] originalBody = response.getBody();
        if (originalBody == null || originalBody.length == 0) {
            log.warn("文本响应体为空，无法处理");
            return;
        }

        String textContent = new String(originalBody, StandardCharsets.UTF_8);
        if (log.isDebugEnabled()) {
            log.debug("处理文本内容，长度: {} bytes", originalBody.length);
        }

        try {
            // 生成文本查看HTML
            String viewerHtml = generateTextViewerHtml(textContent, request.getPath());

            // 更新响应
            byte[] htmlBytes = viewerHtml.getBytes(StandardCharsets.UTF_8);
            response.setBody(htmlBytes);
            response.setContentType("text/html; charset=utf-8");
            response.addHeader("Content-Length", String.valueOf(htmlBytes.length));

            log.info("成功生成文本查看页面，原大小: {} bytes，HTML大小: {} bytes",
                    originalBody.length, htmlBytes.length);

        } catch (Exception e) {
            log.error("处理文本时发生异常", e);
            handleTextError(response, e);
        }
    }

    /**
     * 生成文本查看HTML
     */
    private String generateTextViewerHtml(String textContent, String filePath) {
        String escapedText = escapeHtml(textContent);
        String fileName = extractFileName(filePath);
        String extension = getFileExtension(fileName);
        TextStats stats = analyzeText(textContent);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>文本查看器 - " + escapeHtml(fileName) + "</title>\n" +
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
                "            background: #e8f5e8;\n" +
                "            padding: 15px 20px;\n" +
                "            border-radius: 8px;\n" +
                "            margin-bottom: 20px;\n" +
                "            border-left: 4px solid #4caf50;\n" +
                "        }\n" +
                "        .stats h3 {\n" +
                "            margin: 0 0 10px 0;\n" +
                "            color: #2e7d32;\n" +
                "        }\n" +
                "        .stats-grid {\n" +
                "            display: grid;\n" +
                "            grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));\n" +
                "            gap: 15px;\n" +
                "        }\n" +
                "        .stat-item {\n" +
                "            text-align: center;\n" +
                "        }\n" +
                "        .stat-value {\n" +
                "            font-size: 20px;\n" +
                "            font-weight: bold;\n" +
                "            color: #2e7d32;\n" +
                "        }\n" +
                "        .stat-label {\n" +
                "            font-size: 12px;\n" +
                "            color: #666;\n" +
                "        }\n" +
                "        .text-container {\n" +
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
                "            align-items: center;\n" +
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
                "        .search-box {\n" +
                "            margin-left: auto;\n" +
                "            display: flex;\n" +
                "            gap: 5px;\n" +
                "        }\n" +
                "        .search-input {\n" +
                "            padding: 6px 10px;\n" +
                "            border: 1px solid #ddd;\n" +
                "            border-radius: 4px;\n" +
                "            font-size: 13px;\n" +
                "        }\n" +
                "        .text-content {\n" +
                "            padding: 0;\n" +
                "            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;\n" +
                "            font-size: 14px;\n" +
                "            line-height: 1.5;\n" +
                "            max-height: 70vh;\n" +
                "            overflow: auto;\n" +
                "        }\n" +
                "        .line-numbers {\n" +
                "            display: flex;\n" +
                "        }\n" +
                "        .line-numbers-column {\n" +
                "            background: #f8f9fa;\n" +
                "            padding: 20px 10px;\n" +
                "            border-right: 1px solid #e9ecef;\n" +
                "            color: #6c757d;\n" +
                "            text-align: right;\n" +
                "            user-select: none;\n" +
                "            min-width: 50px;\n" +
                "        }\n" +
                "        .text-lines {\n" +
                "            padding: 20px;\n" +
                "            flex: 1;\n" +
                "            white-space: pre-wrap;\n" +
                "            word-wrap: break-word;\n" +
                "        }\n" +
                "        .highlight {\n" +
                "            background-color: #ffeb3b;\n" +
                "            padding: 2px 4px;\n" +
                "            border-radius: 2px;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"header\">\n" +
                "        <h1><span class=\"icon\">📝</span>文本查看器</h1>\n" +
                "        <div class=\"info\">\n" +
                "            <span>文件: " + escapeHtml(fileName) + "</span>\n" +
                "            <span>类型: " + getFileTypeDescription(extension) + "</span>\n" +
                "            <span>编码: UTF-8</span>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"stats\">\n" +
                "        <h3>📊 文本统计</h3>\n" +
                "        <div class=\"stats-grid\">\n" +
                "            <div class=\"stat-item\">\n" +
                "                <div class=\"stat-value\">" + stats.lineCount + "</div>\n" +
                "                <div class=\"stat-label\">行数</div>\n" +
                "            </div>\n" +
                "            <div class=\"stat-item\">\n" +
                "                <div class=\"stat-value\">" + stats.wordCount + "</div>\n" +
                "                <div class=\"stat-label\">单词数</div>\n" +
                "            </div>\n" +
                "            <div class=\"stat-item\">\n" +
                "                <div class=\"stat-value\">" + stats.charCount + "</div>\n" +
                "                <div class=\"stat-label\">字符数</div>\n" +
                "            </div>\n" +
                "            <div class=\"stat-item\">\n" +
                "                <div class=\"stat-value\">"
                + formatFileSize(textContent.getBytes(StandardCharsets.UTF_8).length) + "</div>\n" +
                "                <div class=\"stat-label\">文件大小</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"text-container\">\n" +
                "        <div class=\"toolbar\">\n" +
                "            <button class=\"btn\" onclick=\"copyToClipboard()\">📋 复制</button>\n" +
                "            <button class=\"btn secondary\" onclick=\"toggleLineNumbers()\">🔢 行号</button>\n" +
                "            <button class=\"btn secondary\" onclick=\"toggleWrap()\">📄 换行</button>\n" +
                "            \n" +
                "            <div class=\"search-box\">\n" +
                "                <input type=\"text\" class=\"search-input\" placeholder=\"搜索文本...\" onkeyup=\"searchText(event)\">\n"
                +
                "                <button class=\"btn secondary\" onclick=\"clearSearch()\">清除</button>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"text-content\" id=\"text-content\">\n" +
                "            <div class=\"line-numbers\" id=\"line-numbers\">\n" +
                "                <div class=\"line-numbers-column\" id=\"line-numbers-column\">"
                + generateLineNumbers(stats.lineCount) + "</div>\n" +
                "                <div class=\"text-lines\" id=\"text-lines\">" + escapedText + "</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        const originalText = " + escapeJs(textContent) + ";\n" +
                "        let showLineNumbers = true;\n" +
                "        let wrapText = true;\n" +
                "        \n" +
                "        function copyToClipboard() {\n" +
                "            navigator.clipboard.writeText(originalText).then(() => {\n" +
                "                alert('文本内容已复制到剪贴板');\n" +
                "            }).catch(err => {\n" +
                "                console.error('复制失败:', err);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function downloadText() {\n" +
                "            const blob = new Blob([originalText], { type: 'text/plain' });\n" +
                "            const url = URL.createObjectURL(blob);\n" +
                "            const a = document.createElement('a');\n" +
                "            a.href = url;\n" +
                "            a.download = '" + escapeHtml(fileName) + "';\n" +
                "            document.body.appendChild(a);\n" +
                "            a.click();\n" +
                "            document.body.removeChild(a);\n" +
                "            URL.revokeObjectURL(url);\n" +
                "        }\n" +
                "        \n" +
                "        function toggleLineNumbers() {\n" +
                "            const column = document.getElementById('line-numbers-column');\n" +
                "            showLineNumbers = !showLineNumbers;\n" +
                "            column.style.display = showLineNumbers ? 'block' : 'none';\n" +
                "        }\n" +
                "        \n" +
                "        function toggleWrap() {\n" +
                "            const textLines = document.getElementById('text-lines');\n" +
                "            wrapText = !wrapText;\n" +
                "            textLines.style.whiteSpace = wrapText ? 'pre-wrap' : 'pre';\n" +
                "        }\n" +
                "        \n" +
                "        function searchText(event) {\n" +
                "            const searchTerm = event.target.value;\n" +
                "            const textLines = document.getElementById('text-lines');\n" +
                "            \n" +
                "            if (searchTerm.length === 0) {\n" +
                "                textLines.innerHTML = " + escapeJs(escapedText) + ";\n" +
                "                return;\n" +
                "            }\n" +
                "            \n" +
                "            const regex = new RegExp('(' + searchTerm.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&') + ')', 'gi');\n"
                +
                "            const highlightedText = " + escapeJs(escapedText)
                + ".replace(regex, '<span class=\"highlight\">$1</span>');\n" +
                "            textLines.innerHTML = highlightedText;\n" +
                "        }\n" +
                "        \n" +
                "        function clearSearch() {\n" +
                "            const searchInput = document.querySelector('.search-input');\n" +
                "            const textLines = document.getElementById('text-lines');\n" +
                "            searchInput.value = '';\n" +
                "            textLines.innerHTML = " + escapeJs(escapedText) + ";\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 文本统计信息类
     */
    private static class TextStats {
        int lineCount = 0;
        int wordCount = 0;
        int charCount = 0;
    }

    /**
     * 分析文本内容
     */
    private TextStats analyzeText(String textContent) {
        TextStats stats = new TextStats();

        if (textContent != null) {
            stats.charCount = textContent.length();
            stats.lineCount = textContent.split("\n").length;

            // 简单的单词计数（按空白字符分割）
            String[] words = textContent.trim().split("\\s+");
            stats.wordCount = words.length == 1 && words[0].isEmpty() ? 0 : words.length;
        }

        return stats;
    }

    /**
     * 生成行号
     */
    private String generateLineNumbers(int lineCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) {
            if (i > 1) {
                sb.append("\n");
            }
            sb.append(i);
        }
        return sb.toString();
    }

    /**
     * 获取文件类型描述
     */
    private String getFileTypeDescription(String extension) {
        switch (extension.toLowerCase()) {
            case ".txt":
                return "纯文本";
            case ".log":
                return "日志文件";
            case ".md":
                return "Markdown";
            case ".html":
            case ".htm":
                return "HTML";
            case ".xml":
                return "XML";
            case ".js":
                return "JavaScript";
            case ".java":
                return "Java源码";
            case ".py":
                return "Python";
            case ".db":
                return "SQL";
            case ".sh":
                return "Shell脚本";
            case ".bat":
                return "批处理";
            case ".yml":
            case ".yaml":
                return "YAML";
            case ".properties":
                return "属性文件";
            case ".ini":
                return "配置文件";
            case ".conf":
            case ".cfg":
                return "配置文件";
            default:
                return "文本文件";
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (StringUtils.isEmpty(fileName)) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex).toLowerCase();
        }

        return "";
    }

    /**
     * 提取文件名
     */
    private String extractFileName(String filePath) {
        if (StringUtils.isEmpty(filePath)) {
            return "unknown.txt";
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

        return filePath.isEmpty() ? "unknown.txt" : filePath;
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
     * 处理文本错误
     */
    private void handleTextError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "文本处理失败: " + e.getMessage();
            String errorHtml = generateTextErrorHtml(errorMessage);

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);

            log.error("设置文本错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理文本错误时发生异常", ex);
        }
    }

    /**
     * 生成文本错误页面HTML
     */
    private String generateTextErrorHtml(String errorMessage) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>文本处理失败</title>\n" +
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
                "        <div class=\"title\">文本处理失败</div>\n" +
                "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public String getViewerName() {
        return "TextViewer";
    }

    @Override
    public int getPriority() {
        return 100; // 较低优先级，作为通用文本处理器
    }

    @Override
    public String getDescription() {
        return "文本文件查看器 - 提供文本文件的查看、搜索和统计功能";
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
