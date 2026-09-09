package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.request.parser.DefaultParseConfig;
import com.chua.common.support.network.protocol.request.parser.FormDataParser;
import com.chua.common.support.network.protocol.request.parser.FormParseException;
import com.chua.common.support.network.protocol.request.parser.HttpFormDataProcessor;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * HTTP表单数据解析过滤器
 * <p>
 * 自动解析HTTP请求中的表单数据，支持：
 * 1. application/x-www-form-urlencoded 表单数据解析
 * 2. multipart/form-data 文件上传数据解析
 * 3. 将解析结果存储到ServletRequest的parameters和attributes中
 * 4. 文件上传转换为RequestMultipartFile对象
 * 5. 错误处理和日志记录
 * 6. 可配置的解析参数（文件大小限制、编码等）
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
@Spi("httpFormData")
@SpiDescribe("HTTP表单数据解析过滤器")
@SpiSupport("http")
public class HttpFormDataServletFilter  extends AbstractServletFilter implements ServletFilter {

    /**
     * 表单数据处理器
     */
    private final HttpFormDataProcessor processor;

    /**
     * 是否启用表单解析
     */
    private boolean enabled = true;

    /**
     * 是否在解析失败时继续执行
     */
    private boolean continueOnError = true;

    /**
     * 构造函数（使用默认配置）
     */
    public HttpFormDataServletFilter() {
        this(DefaultParseConfig.createDefault());
    }

    /**
     * 构造函数
     *
     * @param config 解析配置
     */
    public HttpFormDataServletFilter(FormDataParser.ParseConfig config) {
        this.processor = new HttpFormDataProcessor(config);
    }

    /**
     * 构造函数
     *
     * @param processor 表单数据处理器
     */
    public HttpFormDataServletFilter(HttpFormDataProcessor processor) {
        this.processor = processor != null ? processor : new HttpFormDataProcessor();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        // 检查是否启用
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        // 检查是否为POST请求（通常表单数据在POST请求中）
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"PATCH".equalsIgnoreCase(method)) {
            // 非POST/PUT/PATCH请求，直接继续
            chain.doFilter(request, response);
            return;
        }

        // 检查Content-Type是否为表单类型
        String contentType = request.getContentType();
        if (!processor.supports(contentType)) {
            // 不支持的Content-Type，直接继续
            chain.doFilter(request, response);
            return;
        }

        try {
            // 解析表单数据
            long startTime = System.currentTimeMillis();
            processor.processFormData(request);
            long endTime = System.currentTimeMillis();

            log.debug("表单数据解析完成，耗时: {}ms, Content-Type: {}", 
                    endTime - startTime, contentType);

            // 记录解析结果统计
            logParseResults(request);

        } catch (FormParseException e) {
            log.error("表单数据解析失败: {}", e.getDetailedMessage());

            if (!continueOnError) {
                // 设置错误响应
                response.setStatusCode(400);
                response.setStatusMessage("Bad Request");
                response.setBodyString("表单数据解析失败: " + e.getMessage());
                response.setTerminateEarly(true);
                return;
            }

            // 继续执行，但记录错误信息到request属性中
            request.getAttributes().set("formParseError", e);

        } catch (Exception e) {
            log.error("表单数据解析时发生未知错误", e);

            if (!continueOnError) {
                // 设置错误响应
                response.setStatusCode(500);
                response.setStatusMessage("Internal Server Error");
                response.setBodyString("服务器内部错误");
                response.setTerminateEarly(true);
                return;
            }

            // 继续执行，但记录错误信息到request属性中
            request.getAttributes().set("formParseError", e);
        }

        // 继续执行过滤器链
        chain.doFilter(request, response);
    }

    /**
     * 记录解析结果统计
     */
    private void logParseResults(ServletRequest request) {
        if (!log.isDebugEnabled()) {
            return;
        }

        int parameterCount = request.getParameters().size();
        int fileCount = HttpFormDataProcessor.getMultipartFileCount(request);
        long totalFileSize = HttpFormDataProcessor.getTotalMultipartFileSize(request);

        log.debug("表单解析结果 - 参数数量: {}, 文件数量: {}, 文件总大小: {}字节", 
                parameterCount, fileCount, totalFileSize);

        // 记录文件详情
        if (fileCount > 0) {
            HttpFormDataProcessor.getAllMultipartFiles(request).forEach(file -> {
                log.debug("上传文件: {} -> {} ({}字节, {})", 
                        file.getFieldName(), file.getOriginalFilename(), 
                        file.getSize(), file.getContentType());
            });
        }
    }

    @Override
    public String getFilterName() {
        return "HttpFormDataFilter";
    }

    @Override
    public int getOrder() {
        return 10; // 高优先级，在其他业务过滤器之前执行
    }

    @Override
    public String getDescription() {
        return "HTTP表单数据解析过滤器，自动解析POST请求中的表单数据和文件上传";
    }

    @Override
    public boolean supportProtocol(String protocol) {
        // 只支持HTTP协议
        return "http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol);
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS};
    }

    /**
     * 设置是否启用表单解析
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("表单解析过滤器已{}", enabled ? "启用" : "禁用");
        }
    }

    /**
     * 设置是否在解析失败时继续执行
     *
     * @param continueOnError 是否继续执行
     */
    public void setContinueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
        if (log.isDebugEnabled()) {
            log.debug("表单解析失败时{}", continueOnError ? "继续执行" : "中断执行");
        }
    }

    /**
     * 检查是否启用
     *
     * @return 如果启用返回true，否则返回false
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 检查是否在解析失败时继续执行
     *
     * @return 如果继续执行返回true，否则返回false
     */
    public boolean isContinueOnError() {
        return continueOnError;
    }

    /**
     * 获取表单数据处理器
     *
     * @return 处理器实例
     */
    public HttpFormDataProcessor getProcessor() {
        return processor;
    }

    /**
     * 获取过滤器信息
     *
     * @return 过滤器信息字符串
     */
    public String getFilterInfo() {
        return String.format("HttpFormDataServletFilter{enabled=%s, continueOnError=%s, processor=%s}",
                enabled, continueOnError, processor.getClass().getSimpleName());
    }

    @Override
    public String toString() {
        return getFilterInfo();
    }
}
