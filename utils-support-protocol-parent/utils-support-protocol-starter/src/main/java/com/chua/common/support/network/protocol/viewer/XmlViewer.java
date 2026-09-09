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
 * XML文件查看器
 * <p>
 * 专门处理XML文件的查看和语法高亮：
 * 1. XML语法高亮显示
 * 2. 提供复制、压缩、美化功能
 * 3. 支持XML格式化和美化
 * 4. 显示XML结构统计
 * 5. HTML代码压缩优化
 * 6. XML验证和错误提示
 *
 * @author CH
 * @since 2025/8/1
 */
@Slf4j
@Spi("xml")
public class XmlViewer implements Viewer {

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "text/xml",
            "application/xml",
            "application/xhtml+xml",
            "application/rss+xml",
            "application/atom+xml"
    };

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".xml",
            ".xhtml",
            ".rss",
            ".atom",
            ".xsd",
            ".xsl",
            ".xslt",
            ".svg"
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
            log.warn("XML响应体为空，无法处理");
            return;
        }

        String xmlContent = new String(originalBody, StandardCharsets.UTF_8);
        if (log.isDebugEnabled()) {
            log.debug("处理XML内容，长度: {} bytes", originalBody.length);
        }

        try {
            // 生成高亮HTML
            String highlightHtml = generateXmlHighlightHtml(xmlContent, request.getPath());

            // 压缩HTML
            String compressedHtml = compressHtml(highlightHtml);

            // 更新响应
            byte[] htmlBytes = compressedHtml.getBytes(StandardCharsets.UTF_8);
            response.setBody(htmlBytes);
            response.setContentType("text/html; charset=utf-8");
            response.addHeader("Content-Length", String.valueOf(htmlBytes.length));

            log.info("成功生成XML高亮页面，原大小: {} bytes，HTML大小: {} bytes (压缩后)",
                    originalBody.length, htmlBytes.length);

        } catch (Exception e) {
            log.error("处理XML时发生异常", e);
            handleXmlError(response, e);
        }
    }

    /**
     * 生成XML高亮HTML
     */
    private String generateXmlHighlightHtml(String xmlContent, String filePath) {
        String escapedXml = escapeHtml(xmlContent);
        String fileName = extractFileName(filePath);
        String highlightedXml = highlightXml(escapedXml);
        XmlStats stats = analyzeXml(xmlContent);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>XML查看器 - " + escapeHtml(fileName) + "</title>\n" +
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
                "        .title {\n" +
                "            font-size: 24px;\n" +
                "            font-weight: bold;\n" +
                "            color: #333;\n" +
                "            margin-bottom: 10px;\n" +
                "        }\n" +
                "        .stats {\n" +
                "            display: flex;\n" +
                "            gap: 20px;\n" +
                "            flex-wrap: wrap;\n" +
                "            margin-bottom: 15px;\n" +
                "        }\n" +
                "        .stat-item {\n" +
                "            background: #e3f2fd;\n" +
                "            padding: 8px 12px;\n" +
                "            border-radius: 4px;\n" +
                "            font-size: 14px;\n" +
                "            color: #1976d2;\n" +
                "        }\n" +
                "        .actions {\n" +
                "            display: flex;\n" +
                "            gap: 10px;\n" +
                "            flex-wrap: wrap;\n" +
                "        }\n" +
                "        .btn {\n" +
                "            padding: 8px 16px;\n" +
                "            border: none;\n" +
                "            border-radius: 4px;\n" +
                "            cursor: pointer;\n" +
                "            font-size: 14px;\n" +
                "            transition: all 0.3s;\n" +
                "            background: #2196f3;\n" +
                "            color: white;\n" +
                "        }\n" +
                "        .btn:hover {\n" +
                "            background: #1976d2;\n" +
                "            transform: translateY(-1px);\n" +
                "        }\n" +
                "        .btn.secondary {\n" +
                "            background: #ff9800;\n" +
                "        }\n" +
                "        .btn.secondary:hover {\n" +
                "            background: #f57c00;\n" +
                "        }\n" +
                "        .btn.success {\n" +
                "            background: #4caf50;\n" +
                "        }\n" +
                "        .btn.success:hover {\n" +
                "            background: #388e3c;\n" +
                "        }\n" +
                "        .btn.danger {\n" +
                "            background: #f44336;\n" +
                "        }\n" +
                "        .btn.danger:hover {\n" +
                "            background: #d32f2f;\n" +
                "        }\n" +
                "        .xml-container {\n" +
                "            background: #fff;\n" +
                "            border-radius: 8px;\n" +
                "            box-shadow: 0 2px 8px rgba(0,0,0,0.1);\n" +
                "            overflow: hidden;\n" +
                "        }\n" +
                "        .xml-content {\n" +
                "            padding: 20px;\n" +
                "            white-space: pre-wrap;\n" +
                "            overflow-x: auto;\n" +
                "            max-height: 80vh;\n" +
                "            overflow-y: auto;\n" +
                "            font-size: 14px;\n" +
                "            line-height: 1.5;\n" +
                "        }\n" +
                "        .xml-tag {\n" +
                "            color: #1976d2;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "        .xml-attribute {\n" +
                "            color: #388e3c;\n" +
                "        }\n" +
                "        .xml-value {\n" +
                "            color: #d32f2f;\n" +
                "        }\n" +
                "        .xml-comment {\n" +
                "            color: #757575;\n" +
                "            font-style: italic;\n" +
                "        }\n" +
                "        .xml-cdata {\n" +
                "            color: #ff6f00;\n" +
                "            background: #fff3e0;\n" +
                "            padding: 2px 4px;\n" +
                "            border-radius: 2px;\n" +
                "        }\n" +
                "        .xml-declaration {\n" +
                "            color: #9c27b0;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "        .xml-text {\n" +
                "            color: #333;\n" +
                "        }\n" +
                "        .line-numbers {\n" +
                "            background: #f5f5f5;\n" +
                "            border-right: 1px solid #ddd;\n" +
                "            padding: 20px 10px;\n" +
                "            color: #666;\n" +
                "            font-size: 12px;\n" +
                "            line-height: 1.5;\n" +
                "            user-select: none;\n" +
                "            min-width: 40px;\n" +
                "            text-align: right;\n" +
                "        }\n" +
                "        .xml-display {\n" +
                "            display: flex;\n" +
                "        }\n" +
                "        .validation-result {\n" +
                "            margin-top: 15px;\n" +
                "            padding: 10px;\n" +
                "            border-radius: 4px;\n" +
                "            font-size: 14px;\n" +
                "        }\n" +
                "        .validation-success {\n" +
                "            background: #e8f5e8;\n" +
                "            color: #2e7d32;\n" +
                "            border: 1px solid #4caf50;\n" +
                "        }\n" +
                "        .validation-error {\n" +
                "            background: #ffebee;\n" +
                "            color: #c62828;\n" +
                "            border: 1px solid #f44336;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"header\">\n" +
                "        <div class=\"title\">📄 XML查看器 - " + escapeHtml(fileName) + "</div>\n" +
                "        \n" +
                "        <div class=\"stats\">\n" +
                "            <div class=\"stat-item\">📊 元素: " + stats.elementCount + "</div>\n" +
                "            <div class=\"stat-item\">🏷️ 属性: " + stats.attributeCount + "</div>\n" +
                "            <div class=\"stat-item\">💬 注释: " + stats.commentCount + "</div>\n" +
                "            <div class=\"stat-item\">📏 大小: " + formatFileSize(xmlContent.length()) + "</div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"actions\">\n" +
                "            <button class=\"btn\" onclick=\"copyToClipboard()\">📋 复制</button>\n" +
                "            <button class=\"btn secondary\" onclick=\"toggleMinify()\">🗜️ 压缩</button>\n" +
                "            <button class=\"btn success\" onclick=\"formatXml()\">✨ 美化</button>\n" +
                "            <button class=\"btn danger\" onclick=\"validateXml()\">✅ 验证</button>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div id=\"validation-result\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"xml-container\">\n" +
                "        <div class=\"xml-display\">\n" +
                "            <div class=\"line-numbers\" id=\"line-numbers\"></div>\n" +
                "            <div class=\"xml-content\" id=\"xml-content\">" + highlightedXml + "</div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        const originalXml = " + escapeJs(xmlContent) + ";\n" +
                "        let isMinified = false;\n" +
                "        \n" +
                "        // 初始化行号\n" +
                "        updateLineNumbers();\n" +
                "        \n" +
                "        function updateLineNumbers() {\n" +
                "            const content = document.getElementById('xml-content').textContent;\n" +
                "            const lines = content.split('\\n');\n" +
                "            const lineNumbers = document.getElementById('line-numbers');\n" +
                "            lineNumbers.innerHTML = lines.map((_, i) => i + 1).join('\\n');\n" +
                "        }\n" +
                "        \n" +
                "        function copyToClipboard() {\n" +
                "            navigator.clipboard.writeText(originalXml).then(() => {\n" +
                "                alert('XML内容已复制到剪贴板');\n" +
                "            }).catch(err => {\n" +
                "                console.error('复制失败:', err);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function toggleMinify() {\n" +
                "            const content = document.getElementById('xml-content');\n" +
                "            if (isMinified) {\n" +
                "                content.innerHTML = " + escapeJs(highlightedXml) + ";\n" +
                "                isMinified = false;\n" +
                "            } else {\n" +
                "                const minified = originalXml.replace(/\\s+/g, ' ').replace(/> </g, '><').trim();\n" +
                "                content.textContent = minified;\n" +
                "                isMinified = true;\n" +
                "            }\n" +
                "            updateLineNumbers();\n" +
                "        }\n" +
                "        \n" +
                "        function formatXml() {\n" +
                "            const content = document.getElementById('xml-content');\n" +
                "            const formatted = beautifyXml(originalXml);\n" +
                "            const highlighted = highlightXmlText(formatted);\n" +
                "            content.innerHTML = highlighted;\n" +
                "            isMinified = false;\n" +
                "            updateLineNumbers();\n" +
                "        }\n" +
                "        \n" +
                "        function validateXml() {\n" +
                "            const resultDiv = document.getElementById('validation-result');\n" +
                "            try {\n" +
                "                const parser = new DOMParser();\n" +
                "                const xmlDoc = parser.parseFromString(originalXml, 'text/xml');\n" +
                "                const parseError = xmlDoc.getElementsByTagName('parsererror');\n" +
                "                \n" +
                "                if (parseError.length > 0) {\n" +
                "                    resultDiv.className = 'validation-result validation-error';\n" +
                "                    resultDiv.textContent = '❌ XML格式错误: ' + parseError[0].textContent;\n" +
                "                } else {\n" +
                "                    resultDiv.className = 'validation-result validation-success';\n" +
                "                    resultDiv.textContent = '✅ XML格式正确';\n" +
                "                }\n" +
                "            } catch (e) {\n" +
                "                resultDiv.className = 'validation-result validation-error';\n" +
                "                resultDiv.textContent = '❌ XML验证失败: ' + e.message;\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function beautifyXml(xml) {\n" +
                "            // 移除多余空白\n" +
                "            xml = xml.replace(/\\s+/g, ' ').trim();\n" +
                "            \n" +
                "            let formatted = '';\n" +
                "            let indent = 0;\n" +
                "            const tab = '    ';\n" +
                "            \n" +
                "            xml.split(/(<[^>]*>)/).forEach(function(node) {\n" +
                "                if (node.match(/^<\\w[^>]*[^/]>.*$/)) {\n" +
                "                    // 开始标签\n" +
                "                    formatted += tab.repeat(indent) + node + '\\n';\n" +
                "                    indent++;\n" +
                "                } else if (node.match(/^<\\w[^>]*\\/>$/)) {\n" +
                "                    // 自闭合标签\n" +
                "                    formatted += tab.repeat(indent) + node + '\\n';\n" +
                "                } else if (node.match(/^<\\/\\w[^>]*>$/)) {\n" +
                "                    // 结束标签\n" +
                "                    indent--;\n" +
                "                    formatted += tab.repeat(indent) + node + '\\n';\n" +
                "                } else if (node.match(/^<\\?[^>]*\\?>$/)) {\n" +
                "                    // XML声明\n" +
                "                    formatted += node + '\\n';\n" +
                "                } else if (node.match(/^<!--[\\s\\S]*?-->$/)) {\n" +
                "                    // 注释\n" +
                "                    formatted += tab.repeat(indent) + node + '\\n';\n" +
                "                } else if (node.trim()) {\n" +
                "                    // 文本内容\n" +
                "                    formatted += tab.repeat(indent) + node.trim() + '\\n';\n" +
                "                }\n" +
                "            });\n" +
                "            \n" +
                "            return formatted.trim();\n" +
                "        }\n" +
                "        \n" +
                "        function highlightXmlText(xml) {\n" +
                "            return xml\n" +
                "                .replace(/&/g, '&amp;')\n" +
                "                .replace(/</g, '&lt;')\n" +
                "                .replace(/>/g, '&gt;')\n" +
                "                // XML声明\n" +
                "                .replace(/(&lt;\\?[^&]*\\?&gt;)/g, '<span class=\"xml-declaration\">$1</span>')\n" +
                "                // 注释\n" +
                "                .replace(/(&lt;!--[\\s\\S]*?--&gt;)/g, '<span class=\"xml-comment\">$1</span>')\n" +
                "                // CDATA\n" +
                "                .replace(/(&lt;!\\[CDATA\\[[\\s\\S]*?\\]\\]&gt;)/g, '<span class=\"xml-cdata\">$1</span>')\n" +
                "                // 标签\n" +
                "                .replace(/(&lt;\\/?[a-zA-Z][^&]*?&gt;)/g, function(match) {\n" +
                "                    return match\n" +
                "                        .replace(/(&lt;\\/?[a-zA-Z][^\\s&]*)/g, '<span class=\"xml-tag\">$1</span>')\n" +
                "                        .replace(/([a-zA-Z-]+)=([\"'][^\"']*[\"'])/g, '<span class=\"xml-attribute\">$1</span>=<span class=\"xml-value\">$2</span>');\n" +
                "                });\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * XML统计信息类
     */
    private static class XmlStats {
        int elementCount = 0;
        int attributeCount = 0;
        int commentCount = 0;
    }

    /**
     * 分析XML内容
     */
    private XmlStats analyzeXml(String xmlContent) {
        XmlStats stats = new XmlStats();

        // 统计注释
        stats.commentCount = countMatches(xmlContent, "<!--[\\s\\S]*?-->");

        // 移除注释后统计其他内容
        String cleanXml = xmlContent.replaceAll("<!--[\\s\\S]*?-->", "");

        // 统计元素（开始标签和自闭合标签）
        stats.elementCount = countMatches(cleanXml, "<[a-zA-Z][^>]*>");

        // 统计属性
        stats.attributeCount = countMatches(cleanXml, "[a-zA-Z-]+\\s*=\\s*[\"'][^\"']*[\"']");

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
     * 高亮XML语法
     */
    private String highlightXml(String xml) {
        // XML语法高亮
        return xml
                // XML声明
                .replaceAll("(<\\?[^>]*\\?>)", "<span class=\"xml-declaration\">$1</span>")
                // 注释
                .replaceAll("(<!--[\\s\\S]*?-->)", "<span class=\"xml-comment\">$1</span>")
                // CDATA
                .replaceAll("(<!\\[CDATA\\[[\\s\\S]*?\\]\\]>)", "<span class=\"xml-cdata\">$1</span>")
                // 标签
                .replaceAll("(</?[a-zA-Z][^>]*>)", "<span class=\"xml-tag\">$1</span>")
                // 属性
                .replaceAll("([a-zA-Z-]+)\\s*=\\s*([\"'][^\"']*[\"'])", "<span class=\"xml-attribute\">$1</span>=<span class=\"xml-value\">$2</span>");
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
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
            return "unknown.xml";
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

        return filePath.isEmpty() ? "unknown.xml" : filePath;
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
     * 处理XML错误
     */
    private void handleXmlError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "XML处理失败: " + e.getMessage();
            String errorHtml = generateXmlErrorHtml(errorMessage);

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);
            response.addHeader("Content-Length", String.valueOf(errorHtml.getBytes(StandardCharsets.UTF_8).length));

            log.error("设置XML错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理XML错误时发生异常", ex);
        }
    }

    /**
     * 生成XML错误页面HTML
     */
    private String generateXmlErrorHtml(String errorMessage) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>XML处理失败</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 50px; background-color: #f5f5f5; }\n" +
                "        .error { color: #d32f2f; background: #ffebee; padding: 20px; border-radius: 8px; border-left: 4px solid #d32f2f; }\n" +
                "        .title { font-size: 24px; margin-bottom: 10px; font-weight: bold; }\n" +
                "        .message { font-size: 16px; line-height: 1.5; }\n" +
                "        .icon { font-size: 48px; margin-bottom: 20px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"error\">\n" +
                "        <div class=\"icon\">❌</div>\n" +
                "        <div class=\"title\">XML处理失败</div>\n" +
                "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public String getViewerName() {
        return "XmlViewer";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public String getDescription() {
        return "XML文件查看器 - 提供XML语法高亮、美化、压缩、验证和统计功能";
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
