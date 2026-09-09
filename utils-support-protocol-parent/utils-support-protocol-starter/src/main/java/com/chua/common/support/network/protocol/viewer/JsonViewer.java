package com.chua.common.support.network.protocol.viewer;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.text.json.Json;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * JSON文件查看器
 * <p>
 * 专门处理JSON文件的查看和高亮显示：
 * 1. 验证和美化JSON格式
 * 2. 生成带语法高亮的HTML页面
 * 3. 提供复制、下载等功能
 * 4. 支持行号显示
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("json")
public class JsonViewer implements Viewer {

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "application/json",
            "text/json"
    };

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".json"
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
            log.warn("JSON响应体为空，无法处理");
            return;
        }

        String jsonContent = new String(originalBody, StandardCharsets.UTF_8);
        if (log.isDebugEnabled()) {
            log.debug("处理JSON内容，长度: {} bytes", originalBody.length);
        }

        try {
            // 验证和美化JSON
            String formattedJson = formatJson(jsonContent);

            // 生成高亮HTML
            String highlightHtml = generateJsonHighlightHtml(formattedJson, request.getPath());

            // 更新响应
            byte[] htmlBytes = highlightHtml.getBytes(StandardCharsets.UTF_8);
            response.setBody(htmlBytes);
            response.setContentType("text/html; charset=utf-8");
            response.addHeader("Content-Length", String.valueOf(htmlBytes.length));

            log.info("成功生成JSON高亮页面，原大小: {} bytes，HTML大小: {} bytes",
                    originalBody.length, htmlBytes.length);

        } catch (Exception e) {
            log.error("处理JSON时发生异常", e);
            handleJsonError(response, e);
        }
    }

    /**
     * 格式化JSON
     */
    private String formatJson(String jsonContent) {
        try {
            // 尝试解析和美化JSON
            Object jsonObject = Json.fromJson(jsonContent, Object.class);
            return Json.toPrettyJson(jsonObject);
        } catch (Exception e) {
            log.warn("JSON格式化失败，使用原始内容: {}", e.getMessage());
            return jsonContent;
        }
    }

    /**
     * 生成JSON高亮HTML
     */
    private String generateJsonHighlightHtml(String jsonContent, String filePath) {
        String escapedJson = escapeHtml(jsonContent);
        String fileName = extractFileName(filePath);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>JSON查看器 - " + escapeHtml(fileName) + "</title>\n" +
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
                "        }\n" +
                "        .json-container {\n" +
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
                "        .json-content {\n" +
                "            padding: 20px;\n" +
                "            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;\n" +
                "            font-size: 14px;\n" +
                "            line-height: 1.5;\n" +
                "            max-height: 70vh;\n" +
                "            overflow: auto;\n" +
                "            white-space: pre-wrap;\n" +
                "            word-wrap: break-word;\n" +
                "        }\n" +
                "        .json-syntax {\n" +
                "            color: #333;\n" +
                "        }\n" +
                "        .json-key {\n" +
                "            color: #d73a49;\n" +
                "            font-weight: bold;\n" +
                "        }\n" +
                "        .json-string {\n" +
                "            color: #032f62;\n" +
                "        }\n" +
                "        .json-number {\n" +
                "            color: #005cc5;\n" +
                "        }\n" +
                "        .json-boolean {\n" +
                "            color: #e36209;\n" +
                "        }\n" +
                "        .json-null {\n" +
                "            color: #6f42c1;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"header\">\n" +
                "        <h1><span class=\"icon\">📄</span>JSON查看器</h1>\n" +
                "        <div class=\"info\">文件: " + escapeHtml(fileName) + " | 大小: " + jsonContent.length()
                + " 字符</div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"json-container\">\n" +
                "        <div class=\"toolbar\">\n" +
                "            <button class=\"btn\" onclick=\"copyToClipboard()\">📋 复制</button>\n" +
                "            <button class=\"btn\" onclick=\"downloadJson()\">💾 下载</button>\n" +
                "            <button class=\"btn secondary\" onclick=\"toggleFormat()\">🎨 格式化</button>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"json-content\" id=\"json-content\">" + highlightJson(escapedJson) + "</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        const originalJson = " + Json.toJson(jsonContent) + ";\n" +
                "        let isFormatted = true;\n" +
                "        \n" +
                "        function copyToClipboard() {\n" +
                "            navigator.clipboard.writeText(originalJson).then(() => {\n" +
                "                alert('JSON内容已复制到剪贴板');\n" +
                "            }).catch(err => {\n" +
                "                console.error('复制失败:', err);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function downloadJson() {\n" +
                "            const blob = new Blob([originalJson], { type: 'application/json' });\n" +
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
                "        function toggleFormat() {\n" +
                "            const content = document.getElementById('json-content');\n" +
                "            if (isFormatted) {\n" +
                "                content.textContent = JSON.stringify(JSON.parse(originalJson));\n" +
                "                isFormatted = false;\n" +
                "            } else {\n" +
                "                content.innerHTML = " + Json.toJson(highlightJson(escapedJson)) + ";\n" +
                "                isFormatted = true;\n" +
                "            }\n" +
                "        }\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 高亮JSON语法
     */
    private String highlightJson(String json) {
        // 简单的JSON语法高亮
        return json.replaceAll("\"([^\"]+)\"\\s*:", "<span class=\"json-key\">\"$1\"</span>:")
                .replaceAll(":\\s*\"([^\"]+)\"", ": <span class=\"json-string\">\"$1\"</span>")
                .replaceAll(":\\s*(\\d+\\.?\\d*)", ": <span class=\"json-number\">$1</span>")
                .replaceAll(":\\s*(true|false)", ": <span class=\"json-boolean\">$1</span>")
                .replaceAll(":\\s*(null)", ": <span class=\"json-null\">$1</span>");
    }

    /**
     * 提取文件名
     */
    private String extractFileName(String filePath) {
        if (StringUtils.isEmpty(filePath)) {
            return "unknown.json";
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

        return filePath.isEmpty() ? "unknown.json" : filePath;
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
     * 处理JSON错误
     */
    private void handleJsonError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "JSON处理失败: " + e.getMessage();
            String errorHtml = generateJsonErrorHtml(errorMessage);

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);
            response.addHeader("Content-Length", String.valueOf(errorHtml.getBytes(StandardCharsets.UTF_8).length));

            log.error("设置JSON错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理JSON错误时发生异常", ex);
        }
    }

    /**
     * 生成JSON错误页面HTML
     */
    private String generateJsonErrorHtml(String errorMessage) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>JSON处理失败</title>\n" +
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
                "        <div class=\"title\">JSON处理失败</div>\n" +
                "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
                "    </div>\n" +
                "</body>\n" +
                "</html>";
    }

    @Override
    public String getViewerName() {
        return "JsonViewer";
    }

    @Override
    public int getPriority() {
        return 10; // 高优先级
    }

    @Override
    public String getDescription() {
        return "JSON文件查看器 - 提供JSON语法高亮和格式化功能";
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
