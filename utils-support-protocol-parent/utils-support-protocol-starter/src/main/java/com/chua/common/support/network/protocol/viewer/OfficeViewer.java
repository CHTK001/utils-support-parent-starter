package com.chua.common.support.network.protocol.viewer;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.io.file.system.ConvertFileSystem;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Office文档查看器
 * <p>
 * 专门处理Office文档的查看，通过转换为PDF格式实现：
 * 1. 支持Word、Excel、PowerPoint文档
 * 2. 转换为PDF格式以便浏览器预览
 * 3. 处理转换异常和错误
 * 4. 提供转换进度和状态信息
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("office")
public class OfficeViewer implements Viewer {

    private static final String[] SUPPORTED_CONTENT_TYPES = {
            "application/vnd.ms-excel",
//            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msword",
//            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation"
    };

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".xls", ".doc", ".ppt", ".pptx",
            ".rtf", ".odt", ".ods", ".odp"
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
            log.warn("Office文档响应体为空，无法处理");
            return;
        }

        String extension = getFileExtension(request.getPath());
        if (StringUtils.isEmpty(extension)) {
            log.warn("无法确定文件扩展名，跳过处理");
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("处理Office文档，扩展名: {}，大小: {} bytes", extension, originalBody.length);
        }

        try {
            // 转换为PDF
            convertToPdf(response, extension, originalBody);

        } catch (Exception e) {
            log.error("处理Office文档时发生异常", e);
            handleOfficeError(response, e);
        }
    }

    /**
     * 转换为PDF
     */
    private void convertToPdf(ServletResponse response, String extension, byte[] originalBody) throws IOException {
        File tempInputFile = null;
        File tempOutputFile = null;
        try {
            // 创建临时文件
            tempInputFile = File.createTempFile("office_input_", extension);
            tempOutputFile = File.createTempFile("office_output_", ".pdf");
            
            // 写入临时文件
            Files.write(tempInputFile.toPath(), originalBody);
            
            // 查找转换器
            ConvertFileSystem convertSystem = ConvertFileSystem.autoDetect(tempInputFile, "pdf");
            if (convertSystem == null) {
                log.warn("不支持从 {} 转换为 PDF", extension);
                handleUnsupportedFormat(response, extension);
                return;
            }

            log.info("开始转换Office文档: {} -> PDF", extension);
            long startTime = System.currentTimeMillis();

            // 执行转换
            convertSystem.convertTo(tempOutputFile);

            long endTime = System.currentTimeMillis();

            // 读取转换结果
            byte[] pdfBytes = Files.readAllBytes(tempOutputFile.toPath());
            if (pdfBytes.length > 0) {
                response.setBody(pdfBytes);
                response.setContentType("application/pdf");
                response.addHeader("Content-Length", String.valueOf(pdfBytes.length));
                response.addHeader("Content-Disposition", "inline");

                log.info("成功将 {} 格式转换为PDF，原大小: {} bytes，转换后大小: {} bytes，耗时: {} ms",
                        extension, originalBody.length, pdfBytes.length, (endTime - startTime));
            } else {
                log.warn("转换结果为空");
                handleEmptyResult(response);
            }

        } catch (Exception e) {
            log.error("转换为PDF时发生异常: {}", e.getMessage(), e);
            throw new IOException("Office文档转换失败: " + e.getMessage(), e);
        } finally {
            // 清理临时文件
            if (tempInputFile != null && tempInputFile.exists()) {
                try {
                    Files.deleteIfExists(tempInputFile.toPath());
                } catch (Exception e) {
                    log.warn("删除临时输入文件失败: {}", tempInputFile.getAbsolutePath(), e);
                }
            }
            if (tempOutputFile != null && tempOutputFile.exists()) {
                try {
                    Files.deleteIfExists(tempOutputFile.toPath());
                } catch (Exception e) {
                    log.warn("删除临时输出文件失败: {}", tempOutputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String path) {
        if (StringUtils.isEmpty(path)) {
            return "";
        }

        // 移除查询参数
        int queryIndex = path.indexOf('?');
        if (queryIndex != -1) {
            path = path.substring(0, queryIndex);
        }

        // 获取扩展名
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < path.length() - 1) {
            return path.substring(dotIndex).toLowerCase();
        }

        return "";
    }

    /**
     * 处理不支持的格式
     */
    private void handleUnsupportedFormat(ServletResponse response, String extension) {
        String errorMessage = "不支持的Office文档格式: " + extension;
        String errorHtml = generateErrorHtml("格式不支持", errorMessage, "🚫");

        response.setStatusCode(415); // Unsupported Media Type
        response.setContentType("text/html; charset=utf-8");
        response.setBodyString(errorHtml);
    }

    /**
     * 处理空结果
     */
    private void handleEmptyResult(ServletResponse response) {
        String errorMessage = "文档转换完成，但结果为空。可能是文档内容为空或转换过程中出现问题。";
        String errorHtml = generateErrorHtml("转换结果为空", errorMessage, "📄");

        response.setStatusCode(204); // No Content
        response.setContentType("text/html; charset=utf-8");
        response.setBodyString(errorHtml);
    }

    /**
     * 处理Office错误
     */
    private void handleOfficeError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "Office文档处理失败: " + e.getMessage();
            String errorHtml = generateErrorHtml("文档处理失败", errorMessage, "❌");

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);

            log.error("设置Office错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理Office错误时发生异常", ex);
        }
    }

    /**
     * 生成错误页面HTML
     */
    private String generateErrorHtml(String title, String errorMessage, String icon) {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"zh-CN\">\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
               "    <title>" + escapeHtml(title) + "</title>\n" +
               "    <style>\n" +
               "        body {\n" +
               "            font-family: Arial, sans-serif;\n" +
               "            margin: 0;\n" +
               "            padding: 50px;\n" +
               "            background-color: #f5f5f5;\n" +
               "            display: flex;\n" +
               "            justify-content: center;\n" +
               "            align-items: center;\n" +
               "            min-height: 100vh;\n" +
               "        }\n" +
               "        .error-container {\n" +
               "            background: #fff;\n" +
               "            padding: 40px;\n" +
               "            border-radius: 12px;\n" +
               "            box-shadow: 0 4px 12px rgba(0,0,0,0.1);\n" +
               "            text-align: center;\n" +
               "            max-width: 500px;\n" +
               "        }\n" +
               "        .icon {\n" +
               "            font-size: 64px;\n" +
               "            margin-bottom: 20px;\n" +
               "        }\n" +
               "        .title {\n" +
               "            font-size: 28px;\n" +
               "            margin-bottom: 15px;\n" +
               "            color: #333;\n" +
               "            font-weight: bold;\n" +
               "        }\n" +
               "        .message {\n" +
               "            font-size: 16px;\n" +
               "            line-height: 1.6;\n" +
               "            color: #666;\n" +
               "            margin-bottom: 30px;\n" +
               "        }\n" +
               "        .suggestions {\n" +
               "            background: #f8f9fa;\n" +
               "            padding: 20px;\n" +
               "            border-radius: 8px;\n" +
               "            text-align: left;\n" +
               "        }\n" +
               "        .suggestions h4 {\n" +
               "            margin: 0 0 10px 0;\n" +
               "            color: #495057;\n" +
               "        }\n" +
               "        .suggestions ul {\n" +
               "            margin: 0;\n" +
               "            padding-left: 20px;\n" +
               "        }\n" +
               "        .suggestions li {\n" +
               "            margin-bottom: 5px;\n" +
               "            color: #6c757d;\n" +
               "        }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <div class=\"error-container\">\n" +
               "        <div class=\"icon\">" + icon + "</div>\n" +
               "        <div class=\"title\">" + escapeHtml(title) + "</div>\n" +
               "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
               "        \n" +
               "        <div class=\"suggestions\">\n" +
               "            <h4>💡 建议解决方案：</h4>\n" +
               "            <ul>\n" +
               "                <li>检查文档是否损坏或加密</li>\n" +
               "                <li>确认文档格式是否受支持</li>\n" +
               "                <li>尝试重新上传文档</li>\n" +
               "                <li>联系管理员获取帮助</li>\n" +
               "            </ul>\n" +
               "        </div>\n" +
               "    </div>\n" +
               "</body>\n" +
               "</html>";
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

    @Override
    public String getViewerName() {
        return "OfficeViewer";
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public String getDescription() {
        return "Office文档查看器 - 将Office文档转换为PDF格式以便浏览器预览";
    }

    @Override
    public String[] getSupportedContentTypes() {
        return SUPPORTED_CONTENT_TYPES;
    }

    @Override
    public String[] getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public boolean requiresConversion() {
        return true;
    }

    @Override
    public String getTargetFormat() {
        return "pdf";
    }
}
