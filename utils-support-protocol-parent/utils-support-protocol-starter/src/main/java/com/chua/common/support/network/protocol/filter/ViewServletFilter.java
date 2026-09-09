package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.io.file.system.ConvertFileSystemUtils;
import com.chua.common.support.network.protocol.viewer.FileConversionCache;
import com.chua.common.support.network.protocol.viewer.Viewer;
import com.chua.common.support.network.protocol.viewer.ViewerManager;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.core.utils.StringUtils;
import com.chua.common.support.value.Value;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 视图Servlet过滤器<code>(request中的mode必须是preview才会开启)</code>
 * <p>
 * 该过滤器的主要功能是对HTTP响应进行格式转换以支持浏览器预览：
 * 1. 拦截HTTP响应，检查响应的Content-Type和文件格式
 * 2. 使用ViewerManager管理各种文件查看器
 * 3. 支持JSON、CSS、Office文档等多种格式的查看和转换
 * 4. 提供统一的错误处理和日志记录
 * 5. 支持查看器的动态发现和优先级管理
 *
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("view")
@SpiDescribe("视图Servlet过滤器")
@SpiSupport("http")
public class ViewServletFilter  extends AbstractServletFilter implements ServletFilter {

    /**
     * 过滤器名称
     */
    private static final String FILTER_NAME = "ViewServletFilter";

    /**
     * 过滤器优先级
     */
    private static final int PRIORITY = 300;

    /**
     * 查看器管理器
     */
    private final ViewerManager viewerManager;

    /**
     * 文件转换缓存
     */
    private final FileConversionCache conversionCache;

    /**
     * 禁止预览的扩展名集合（黑名单）
     * 如果文件扩展名在此集合中，则跳过预览处理
     */
    private final Set<String> disabledExtensions = new HashSet<>();

    /**
     * 允许预览的扩展名集合（白名单）
     * 如果不为空，只有在此集合中的扩展名才能预览
     */
    private final Set<String> allowedExtensions = new HashSet<>();

    /**
     * 是否启用白名单模式
     * true: 只允许白名单中的扩展名预览
     * false: 黑名单模式，禁止黑名单中的扩展名预览
     */
    private boolean whitelistMode = false;

    public ViewServletFilter() {
        this(true);
    }

    /**
     * 构造函数
     */
    public ViewServletFilter(boolean useRemoteResources) {
        this.viewerManager = ViewerManager.getInstance(useRemoteResources);
        this.conversionCache = FileConversionCache.getInstance();
    }

    /**
     * 获取查看器管理器
     *
     * @return 查看器管理器
     */
    public ViewerManager getViewerManager() {
        return viewerManager;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("ViewServletFilter开始处理请求: {} {}", request.getMethod(), request.getPath());
        }

        // 继续执行过滤器链
        chain.doFilter(request, response);
        // 必须是preview模式才支持
        Value<Object> mode = request.getAttributeAsValue("mode");
        if (!mode.is("preview")) {
            return;
        }
        // 检查响应是否需要处理
        if (shouldProcessResponse(request, response)) {
            processResponse(request, response);
        }
    }

    /**
     * 检查响应是否需要处理
     */
    private boolean shouldProcessResponse(ServletRequest request, ServletResponse response) {
        // 检查是否为下载模式请求
        String downloadParam = request.getParameter("download");
        if ("true".equals(downloadParam) || "1".equals(downloadParam)) {
            if (log.isDebugEnabled()) {
                log.debug("检测到下载模式请求，跳过ViewServletFilter处理");
            }
            return false;
        }

        // 检查其他下载相关参数
        String modeParam = request.getParameter("mode");
        if ("download".equals(modeParam) || "raw".equals(modeParam)) {
            if (log.isDebugEnabled()) {
                log.debug("检测到下载模式请求 (mode={}), 跳过ViewServletFilter处理", modeParam);
            }
            return false;
        }

        // 检查响应是否已提交
        if (response.isCommitted()) {
            if (log.isDebugEnabled()) {
                log.debug("响应提交，跳过处理");
            }
            return false;
        }

        // 检查是否有响应体
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            if (log.isDebugEnabled()) {
                log.debug("响应体为空，跳过处理");
            }
            return false;
        }

        // 检查状态码
        int statusCode = response.getStatusCode();
        if (statusCode < 200 || statusCode >= 300) {
            if (log.isDebugEnabled()) {
                log.debug("响应状态码为 {}，跳过处理", statusCode);
            }
            return false;
        }

        // 检查文件扩展名是否允许预览
        String path = request.getPath();
        String extension = getFileExtension(path);
        if (!isPreviewAllowed(extension)) {
            if (log.isDebugEnabled()) {
                log.debug("文件扩展名 {} 不允许预览，跳过处理", extension);
            }
            return false;
        }

        return true;
    }

    /**
     * 检查文件扩展名是否允许预览
     *
     * @param extension 文件扩展名（包含点号，如 .pdf）
     * @return 是否允许预览
     */
    private boolean isPreviewAllowed(String extension) {
        if (StringUtils.isEmpty(extension)) {
            return true;
        }

        // 统一转小写并移除点号
        String ext = extension.toLowerCase();
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }

        // 白名单模式：只有在白名单中的才能预览
        if (whitelistMode && !allowedExtensions.isEmpty()) {
            boolean allowed = allowedExtensions.contains(ext);
            if (!allowed) {
                if (log.isDebugEnabled()) {
                    log.debug("白名单模式: 扩展名 {} 不在允许列表中", ext);
                }
            }
            return allowed;
        }

        // 黑名单模式：在黑名单中的不能预览
        if (disabledExtensions.contains(ext)) {
            if (log.isDebugEnabled()) {
                log.debug("黑名单模式: 扩展名 {} 在禁止列表中", ext);
            }
            return false;
        }

        return true;
    }

    /**
     * 处理响应
     */
    private void processResponse(ServletRequest request, ServletResponse response) {
        try {
            String contentType = response.getContentType();
            if(contentType.startsWith("image/") && !contentType.endsWith("dwg")) {
                return;
            }
            String path = request.getPath();
            String extension = getFileExtension(path);

            if (log.isDebugEnabled()) {
                log.debug("处理响应 - Content-Type: {}, 扩展名: {}", contentType, extension);
            }

            // 查找合适的查看器
            Viewer viewer = viewerManager.findViewer(contentType, extension, request, response);
            if (viewer == null) {
                if (log.isDebugEnabled()) {
                    log.debug("未找到合适的查看器处理文件: Content-Type={}, 扩展名={}", contentType, extension);
                }

                // 返回无法预览的HTML页面
                String unsupportedHtml = generateUnsupportedFileHtml(contentType, extension, request);
                response.setBody(unsupportedHtml.getBytes(StandardCharsets.UTF_8));
                response.setContentType("text/html; charset=utf-8");
                response.addHeader("Content-Length", String.valueOf(unsupportedHtml.getBytes(StandardCharsets.UTF_8).length));
                return;
            }

            // 处理文件转换（带缓存）
            boolean processed = processWithCache(viewer, request, response, contentType, extension);

            if (processed) {
                if (log.isDebugEnabled()) {
                    log.debug("文件已由查看器 {} 处理", viewer.getViewerName());
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("查看器 {} 处理文件失败", viewer.getViewerName());
                }
            }

        } catch (Exception e) {
            log.error("处理响应时发生异常", e);
            handleProcessError(response, e);
        }
    }

    /**
     * 带缓存的文件处理
     */
    private boolean processWithCache(Viewer viewer, ServletRequest request, ServletResponse response,
            String contentType, String extension) {
        try {
            byte[] originalData = response.getBody();
            if (originalData == null || originalData.length == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("响应体为空，跳过缓存处理");
                }
                return false;
            }

            String viewerName = viewer.getViewerName();
            String sourceType = determineSourceType(contentType, extension);
            String targetType = viewer.getTargetFormat();

            // 使用SPI机制检查是否支持转换为HTML
            boolean supportsHtmlConversion = checkHtmlConversionSupport(sourceType, extension);

            // 如果不是下载模式，且支持HTML转换，直接返回HTML预览页面
            if (supportsHtmlConversion && "html".equals(targetType)) {
                if (log.isDebugEnabled()) {
                    log.debug("返回HTML预览页面: {} (查看器: {}, 支持HTML转换: {})", sourceType, viewerName, supportsHtmlConversion);
                }

                // 直接调用查看器生成HTML预览页面
                viewer.process(request, response);
                return true;
            }

            // 下载模式或非HTML格式，进行缓存处理
            // 尝试从缓存获取转换结果
            byte[] cachedData = conversionCache.get(originalData, sourceType, targetType, viewerName);
            if (cachedData != null) {
                if (log.isDebugEnabled()) {
                    log.debug("从磁盘缓存获取转换结果: {} -> {} (查看器: {})", sourceType, targetType, viewerName);
                }

                // 更新响应
                response.setBody(cachedData);
                updateResponseForTargetType(response, targetType);
                return true;
            }

            // 缓存未命中，执行转换
            if (log.isDebugEnabled()) {
                log.debug("缓存未命中，执行文件转换: {} -> {} (查看器: {})", sourceType, targetType, viewerName);
            }

            // 备份原始响应数据
            byte[] originalResponseData = originalData.clone();
            String originalContentType = response.getContentType();

            // 执行查看器处理
            viewer.process(request, response);

            // 检查是否转换成功
            byte[] convertedData = response.getBody();
            if (convertedData != null && convertedData.length > 0 &&
                    !java.util.Arrays.equals(originalResponseData, convertedData)) {

                // 转换成功，缓存结果
                conversionCache.put(originalResponseData, sourceType, targetType, viewerName, convertedData);
                log.debug("转换成功并已缓存: {} -> {} (原大小: {} bytes, 转换后: {} bytes)",
                        sourceType, targetType, originalResponseData.length, convertedData.length);
                return true;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("转换未产生新数据或转换失败");
                }
                return false;
            }

        } catch (Exception e) {
            log.error("带缓存的文件处理失败", e);
            return false;
        }
    }

    /**
     * 确定源文件类型
     */
    private String determineSourceType(String contentType, String extension) {
        if (StringUtils.isNotEmpty(extension)) {
            return extension.substring(1); // 移除点号
        }
        if (StringUtils.isNotEmpty(contentType)) {
            String mainType = contentType.split(";")[0].trim();
            if (mainType.contains("/")) {
                return mainType.split("/")[1];
            }
        }
        return "unknown";
    }

    /**
     * 根据目标类型更新响应
     */
    private void updateResponseForTargetType(ServletResponse response, String targetType) {
        switch (targetType.toLowerCase()) {
            case "pdf":
                response.setContentType("application/pdf");
                response.addHeader("Content-Disposition", "inline");
                break;
            case "html":
                response.setContentType("text/html; charset=utf-8");
                break;
            case "json":
                response.setContentType("application/json; charset=utf-8");
                break;
            case "xml":
                response.setContentType("application/xml; charset=utf-8");
                break;
            default:
                // 保持原有Content-Type
                break;
        }

        // 更新Content-Length
        byte[] body = response.getBody();
        if (body != null) {
            response.addHeader("Content-Length", String.valueOf(body.length));
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
     * 处理处理错误
     */
    private void handleProcessError(ServletResponse response, Exception e) {
        try {
            String errorMessage = "文件处理失败: " + e.getMessage();
            String errorHtml = generateErrorHtml(errorMessage);

            response.setStatusCode(500);
            response.setContentType("text/html; charset=utf-8");
            response.setBodyString(errorHtml);

            log.error("设置处理错误响应: {}", errorMessage);

        } catch (Exception ex) {
            log.error("处理错误时发生异常", ex);
        }
    }

    /**
     * 生成错误页面HTML
     */
    private String generateErrorHtml(String errorMessage) {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>文件处理失败</title>\n" +
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
                "        .info {\n" +
                "            background: #e3f2fd;\n" +
                "            padding: 15px;\n" +
                "            border-radius: 8px;\n" +
                "            border-left: 4px solid #2196f3;\n" +
                "            text-align: left;\n" +
                "            font-size: 14px;\n" +
                "            color: #1976d2;\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"error-container\">\n" +
                "        <div class=\"icon\">⚠️</div>\n" +
                "        <div class=\"title\">文件处理失败</div>\n" +
                "        <div class=\"message\">" + escapeHtml(errorMessage) + "</div>\n" +
                "        \n" +
                "        <div class=\"info\">\n" +
                "            <strong>提示：</strong>ViewServletFilter支持多种文件格式的查看和转换，\n" +
                "            包括JSON、CSS、Office文档等。如果遇到问题，请检查文件格式是否受支持。\n" +
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
    public String getFilterName() {
        return FILTER_NAME;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public String getDescription() {
        return "视图过滤器 - 将不可预览的文件格式转换为PDF以支持浏览器预览，支持转换结果缓存";
    }

    /**
     * 获取转换缓存统计信息
     */
    public FileConversionCache.CacheStats getCacheStats() {
        return conversionCache.getStats();
    }

    /**
     * 清空转换缓存
     */
    public void clearCache() {
        conversionCache.clearAll();
        log.info("已清空ViewServletFilter转换缓存");
    }

    /**
     * 获取缓存实例（高级管理）
     */
    public FileConversionCache getConversionCache() {
        return conversionCache;
    }

    // ==================== 预览权限配置方法 ====================

    /**
     * 添加禁止预览的扩展名（黑名单）
     *
     * @param extensions 扩展名列表（不含点号，如 "exe", "dll"）
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter addDisabledExtensions(String... extensions) {
        if (extensions != null) {
            for (String ext : extensions) {
                if (StringUtils.isNotEmpty(ext)) {
                    disabledExtensions.add(ext.toLowerCase().replace(".", ""));
                }
            }
            log.info("添加禁止预览扩展名: {}", Arrays.toString(extensions));
        }
        return this;
    }

    /**
     * 移除禁止预览的扩展名
     *
     * @param extensions 扩展名列表
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter removeDisabledExtensions(String... extensions) {
        if (extensions != null) {
            for (String ext : extensions) {
                if (StringUtils.isNotEmpty(ext)) {
                    disabledExtensions.remove(ext.toLowerCase().replace(".", ""));
                }
            }
            log.info("已移除禁止预览扩展名: {}", Arrays.toString(extensions));
        }
        return this;
    }

    /**
     * 清空禁止预览的扩展名列表
     *
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter clearDisabledExtensions() {
        disabledExtensions.clear();
        log.info("已清空禁止预览扩展名列表");
        return this;
    }

    /**
     * 获取禁止预览的扩展名列表
     *
     * @return 禁止预览的扩展名集合（只读）
     */
    public Set<String> getDisabledExtensions() {
        return Collections.unmodifiableSet(disabledExtensions);
    }

    /**
     * 添加允许预览的扩展名（白名单）
     *
     * @param extensions 扩展名列表（不含点号，如 "pdf", "txt"）
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter addAllowedExtensions(String... extensions) {
        if (extensions != null) {
            for (String ext : extensions) {
                if (StringUtils.isNotEmpty(ext)) {
                    allowedExtensions.add(ext.toLowerCase().replace(".", ""));
                }
            }
            log.info("添加允许预览扩展名: {}", Arrays.toString(extensions));
        }
        return this;
    }

    /**
     * 移除允许预览的扩展名
     *
     * @param extensions 扩展名列表
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter removeAllowedExtensions(String... extensions) {
        if (extensions != null) {
            for (String ext : extensions) {
                if (StringUtils.isNotEmpty(ext)) {
                    allowedExtensions.remove(ext.toLowerCase().replace(".", ""));
                }
            }
            log.info("已移除允许预览扩展名: {}", Arrays.toString(extensions));
        }
        return this;
    }

    /**
     * 清空允许预览的扩展名列表
     *
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter clearAllowedExtensions() {
        allowedExtensions.clear();
        log.info("已清空允许预览扩展名列表");
        return this;
    }

    /**
     * 获取允许预览的扩展名列表
     *
     * @return 允许预览的扩展名集合（只读）
     */
    public Set<String> getAllowedExtensions() {
        return Collections.unmodifiableSet(allowedExtensions);
    }

    /**
     * 设置是否启用白名单模式
     * <p>
     * 白名单模式：只有在白名单中的扩展名才能预览
     * 黑名单模式：除了黑名单中的扩展名外都能预览
     *
     * @param enabled true启用白名单模式，false启用黑名单模式
     * @return 当前实例，支持链式调用
     */
    public ViewServletFilter setWhitelistMode(boolean enabled) {
        this.whitelistMode = enabled;
        log.info("预览模式设置为: {}", enabled ? "白名单模式" : "黑名单模式");
        return this;
    }

    /**
     * 检查是否为白名单模式
     *
     * @return true为白名单模式，false为黑名单模式
     */
    public boolean isWhitelistMode() {
        return whitelistMode;
    }

    /**
     * 配置预览规则
     * <p>
     * 示例:
     * <pre>
     * filter.configurePreview()
     *       .disableExtensions("exe", "dll", "bat")  // 禁止这些扩展名预览
     *       .enableWhitelist(false);                  // 使用黑名单模式
     * </pre>
     *
     * @return 预览配置构建器
     */
    public PreviewConfigBuilder configurePreview() {
        return new PreviewConfigBuilder(this);
    }

    /**
     * 预览配置构建器
     * 提供流式API配置预览规则
     *
     * @author CH
     * @since 2024/12/08
     */
    public static class PreviewConfigBuilder {
        private final ViewServletFilter filter;

        public PreviewConfigBuilder(ViewServletFilter filter) {
            this.filter = filter;
        }

        /**
         * 禁止指定扩展名预览
         *
         * @param extensions 扩展名列表
         * @return 当前构建器
         */
        public PreviewConfigBuilder disableExtensions(String... extensions) {
            filter.addDisabledExtensions(extensions);
            return this;
        }

        /**
         * 允许指定扩展名预览
         *
         * @param extensions 扩展名列表
         * @return 当前构建器
         */
        public PreviewConfigBuilder allowExtensions(String... extensions) {
            filter.addAllowedExtensions(extensions);
            return this;
        }

        /**
         * 设置白名单模式
         *
         * @param enabled true启用白名单模式
         * @return 当前构建器
         */
        public PreviewConfigBuilder enableWhitelist(boolean enabled) {
            filter.setWhitelistMode(enabled);
            return this;
        }

        /**
         * 清空所有配置
         *
         * @return 当前构建器
         */
        public PreviewConfigBuilder clearAll() {
            filter.clearDisabledExtensions();
            filter.clearAllowedExtensions();
            filter.setWhitelistMode(false);
            return this;
        }

        /**
         * 完成配置并返回过滤器实例
         *
         * @return 过滤器实例
         */
        public ViewServletFilter build() {
            return filter;
        }
    }

    /**
     * 使用SPI机制检查是否支持转换为HTML
     *
     * @param sourceType 源文件类型
     * @param extension  文件扩展名
     * @return 是否支持HTML转换
     */
    private boolean checkHtmlConversionSupport(String sourceType, String extension) {
        return ConvertFileSystemUtils.supportedTypes(sourceType, "html");
    }

    /**
     * 生成不支持文件类型的HTML页面
     *
     * @param contentType 文件内容类型
     * @param extension   文件扩展名
     * @param request     请求对象
     * @return HTML页面内容
     */
    private String generateUnsupportedFileHtml(String contentType, String extension, ServletRequest request) {
        String fileName = getFileNameFromRequest(request);
        String fileType = determineFileType(contentType, extension);

        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>无法预览 - " + escapeHtml(fileName) + "</title>\n" +
                "    <style>\n" +
                "        body {\n" +
                "            margin: 0;\n" +
                "            padding: 0;\n" +
                "            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "            min-height: 100vh;\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "            justify-content: center;\n" +
                "        }\n" +
                "        \n" +
                "        .container {\n" +
                "            background: white;\n" +
                "            border-radius: 12px;\n" +
                "            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);\n" +
                "            padding: 40px;\n" +
                "            text-align: center;\n" +
                "            max-width: 500px;\n" +
                "            margin: 20px;\n" +
                "        }\n" +
                "        \n" +
                "        .icon {\n" +
                "            width: 80px;\n" +
                "            height: 80px;\n" +
                "            margin: 0 auto 20px;\n" +
                "            background: #f8f9fa;\n" +
                "            border-radius: 50%;\n" +
                "            display: flex;\n" +
                "            align-items: center;\n" +
                "            justify-content: center;\n" +
                "            font-size: 36px;\n" +
                "            color: #6c757d;\n" +
                "        }\n" +
                "        \n" +
                "        h1 {\n" +
                "            color: #343a40;\n" +
                "            margin: 0 0 10px 0;\n" +
                "            font-size: 24px;\n" +
                "            font-weight: 600;\n" +
                "        }\n" +
                "        \n" +
                "        .subtitle {\n" +
                "            color: #6c757d;\n" +
                "            margin: 0 0 20px 0;\n" +
                "            font-size: 16px;\n" +
                "        }\n" +
                "        \n" +
                "        .file-info {\n" +
                "            background: #f8f9fa;\n" +
                "            border-radius: 8px;\n" +
                "            padding: 20px;\n" +
                "            margin: 20px 0;\n" +
                "            text-align: left;\n" +
                "        }\n" +
                "        \n" +
                "        .file-info-item {\n" +
                "            display: flex;\n" +
                "            justify-content: space-between;\n" +
                "            margin: 8px 0;\n" +
                "            font-size: 14px;\n" +
                "        }\n" +
                "        \n" +
                "        .file-info-label {\n" +
                "            font-weight: 500;\n" +
                "            color: #495057;\n" +
                "        }\n" +
                "        \n" +
                "        .file-info-value {\n" +
                "            color: #6c757d;\n" +
                "            font-family: 'Courier New', monospace;\n" +
                "        }\n" +
                "        \n" +
                "        .actions {\n" +
                "            margin-top: 30px;\n" +
                "        }\n" +
                "        \n" +
                "        .btn {\n" +
                "            display: inline-block;\n" +
                "            padding: 12px 24px;\n" +
                "            margin: 0 8px;\n" +
                "            border: none;\n" +
                "            border-radius: 6px;\n" +
                "            font-size: 14px;\n" +
                "            font-weight: 500;\n" +
                "            text-decoration: none;\n" +
                "            cursor: pointer;\n" +
                "            transition: all 0.2s ease;\n" +
                "        }\n" +
                "        \n" +
                "        .btn-primary {\n" +
                "            background: #007bff;\n" +
                "            color: white;\n" +
                "        }\n" +
                "        \n" +
                "        .btn-primary:hover {\n" +
                "            background: #0056b3;\n" +
                "            transform: translateY(-1px);\n" +
                "        }\n" +
                "        \n" +
                "        .btn-secondary {\n" +
                "            background: #6c757d;\n" +
                "            color: white;\n" +
                "        }\n" +
                "        \n" +
                "        .btn-secondary:hover {\n" +
                "            background: #545b62;\n" +
                "            transform: translateY(-1px);\n" +
                "        }\n" +
                "        \n" +
                "        .supported-formats {\n" +
                "            margin-top: 30px;\n" +
                "            padding-top: 20px;\n" +
                "            border-top: 1px solid #dee2e6;\n" +
                "        }\n" +
                "        \n" +
                "        .supported-formats h3 {\n" +
                "            color: #495057;\n" +
                "            font-size: 16px;\n" +
                "            margin: 0 0 15px 0;\n" +
                "        }\n" +
                "        \n" +
                "        .format-list {\n" +
                "            display: flex;\n" +
                "            flex-wrap: wrap;\n" +
                "            gap: 8px;\n" +
                "            justify-content: center;\n" +
                "        }\n" +
                "        \n" +
                "        .format-tag {\n" +
                "            background: #e9ecef;\n" +
                "            color: #495057;\n" +
                "            padding: 4px 8px;\n" +
                "            border-radius: 4px;\n" +
                "            font-size: 12px;\n" +
                "            font-family: 'Courier New', monospace;\n" +
                "        }\n" +
                "        \n" +
                "        @media (max-width: 768px) {\n" +
                "            .container {\n" +
                "                margin: 10px;\n" +
                "                padding: 30px 20px;\n" +
                "            }\n" +
                "            \n" +
                "            .btn {\n" +
                "                display: block;\n" +
                "                margin: 8px 0;\n" +
                "            }\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"icon\">📄</div>\n" +
                "        <h1>无法预览此文件</h1>\n" +
                "        <p class=\"subtitle\">当前文件格式暂不支持在线预览</p>\n" +
                "        \n" +
                "        <div class=\"file-info\">\n" +
                "            <div class=\"file-info-item\">\n" +
                "                <span class=\"file-info-label\">文件名：</span>\n" +
                "                <span class=\"file-info-value\">" + escapeHtml(fileName) + "</span>\n" +
                "            </div>\n" +
                "            <div class=\"file-info-item\">\n" +
                "                <span class=\"file-info-label\">文件类型：</span>\n" +
                "                <span class=\"file-info-value\">" + escapeHtml(fileType) + "</span>\n" +
                "            </div>\n" +
                "            <div class=\"file-info-item\">\n" +
                "                <span class=\"file-info-label\">扩展名：</span>\n" +
                "                <span class=\"file-info-value\">" + escapeHtml(extension != null ? extension : "未知")
                + "</span>\n" +
                "            </div>\n" +
                "            <div class=\"file-info-item\">\n" +
                "                <span class=\"file-info-label\">MIME类型：</span>\n" +
                "                <span class=\"file-info-value\">"
                + escapeHtml(contentType != null ? contentType : "未知") + "</span>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        <div class=\"actions\">\n" +
                "            <button class=\"btn btn-primary\" onclick=\"downloadFile()\">下载文件</button>\n" +
                "            <button class=\"btn btn-secondary\" onclick=\"goBack()\">返回</button>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <script>\n" +
                "        function downloadFile() {\n" +
                "            // 构造下载URL\n" +
                "            var currentUrl = window.location.href;\n" +
                "            var downloadUrl = currentUrl;\n" +
                "            \n" +
                "            // 添加下载参数\n" +
                "            if (downloadUrl.indexOf('?') > -1) {\n" +
                "                downloadUrl += '&download=true';\n" +
                "            } else {\n" +
                "                downloadUrl += '?download=true';\n" +
                "            }\n" +
                "            \n" +
                "            // 创建下载链接\n" +
                "            var link = document.createElement('a');\n" +
                "            link.href = downloadUrl;\n" +
                "            link.download = '" + escapeHtml(fileName) + "';\n" +
                "            document.body.appendChild(link);\n" +
                "            link.click();\n" +
                "            document.body.removeChild(link);\n" +
                "        }\n" +
                "        \n" +
                "        function goBack() {\n" +
                "            if (window.history.length > 1) {\n" +
                "                window.history.back();\n" +
                "            } else {\n" +
                "                window.close();\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        // 键盘快捷键\n" +
                "        document.addEventListener('keydown', function(e) {\n" +
                "            if (e.key === 'Escape') {\n" +
                "                goBack();\n" +
                "            } else if (e.ctrlKey && e.key === 's') {\n" +
                "                e.preventDefault();\n" +
                "                downloadFile();\n" +
                "            }\n" +
                "        });\n" +
                "        \n" +
                "        // 页面加载完成后的提示\n" +
                "        document.addEventListener('DOMContentLoaded', function() {\n" +
                "            console.log('文件预览不可用: " + escapeHtml(fileType) + "');\n" +
                "        });\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * 从请求中获取文件名
     *
     * @param request 请求对象
     * @return 文件名
     */
    private String getFileNameFromRequest(ServletRequest request) {
        try {
            String requestUri = request.getRequestURI();
            if (requestUri != null) {
                int lastSlash = requestUri.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < requestUri.length() - 1) {
                    String fileName = requestUri.substring(lastSlash + 1);
                    // 移除查询参数
                    int questionMark = fileName.indexOf('?');
                    if (questionMark > 0) {
                        fileName = fileName.substring(0, questionMark);
                    }
                    return fileName;
                }
            }
            return "未知文件";
        } catch (Exception e) {
            return "未知文件";
        }
    }

    /**
     * 确定文件类型描述
     *
     * @param contentType 内容类型
     * @param extension   文件扩展名
     * @return 文件类型描述
     */
    private String determineFileType(String contentType, String extension) {
        // 根据扩展名确定文件类型
        if (extension != null) {
            switch (extension.toLowerCase()) {
                case ".pdf":
                    return "PDF文档";
                case ".doc":
                case ".docx":
                    return "Word文档";
                case ".xls":
                case ".xlsx":
                    return "Excel表格";
                case ".ppt":
                case ".pptx":
                    return "PowerPoint演示文稿";
                case ".txt":
                    return "文本文件";
                case ".md":
                    return "Markdown文档";
                case ".html":
                case ".htm":
                    return "HTML网页";
                case ".xml":
                    return "XML文档";
                case ".json":
                    return "JSON数据";
                case ".csv":
                    return "CSV表格";
                case ".zip":
                case ".rar":
                case ".7z":
                    return "压缩文件";
                case ".jpg":
                case ".jpeg":
                case ".png":
                case ".gif":
                case ".bmp":
                    return "图片文件";
                case ".mp4":
                case ".avi":
                case ".mov":
                case ".wmv":
                    return "视频文件";
                case ".mp3":
                case ".wav":
                case ".flac":
                case ".aac":
                    return "音频文件";
                case ".ofd":
                    return "OFD文档";
                default:
                    return "未知格式";
            }
        }

        // 根据MIME类型确定文件类型
        if (contentType != null) {
            if (contentType.startsWith("text/"))
                return "文本文件";
            if (contentType.startsWith("image/"))
                return "图片文件";
            if (contentType.startsWith("video/"))
                return "视频文件";
            if (contentType.startsWith("audio/"))
                return "音频文件";
            if (contentType.startsWith("application/pdf"))
                return "PDF文档";
            if (contentType.contains("excel") || contentType.contains("spreadsheet"))
                return "Excel表格";
            if (contentType.contains("word") || contentType.contains("document"))
                return "Word文档";
            if (contentType.contains("powerpoint") || contentType.contains("presentation"))
                return "PowerPoint演示文稿";
        }

        return "未知格式";
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS};
    }
}
