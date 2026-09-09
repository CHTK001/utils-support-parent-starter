package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.FilterOption;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 日志记录Servlet过滤器
 * <p>
 * 记录请求和响应的详细信息，调试和监控。
 * 支持热重载配置更新。
 *
 * @author CH
 * @since 2024/7/8
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("logging")
@SpiDescribe(value = "请求响应日志记录过滤器")
public class LoggingServletFilter extends UpgradeServletFilter<LoggingServletFilter.LoggingConfig> {

    /**
     * 构造函数
     */
    public LoggingServletFilter() {
        super("LoggingFilter");

        // 设置默认配置
        LoggingConfig defaultConfig = new LoggingConfig();
        defaultConfig.setEnabled(true);
        defaultConfig.setLogRequestHeaders(true);
        defaultConfig.setLogResponseHeaders(true);
        defaultConfig.setLogRequestBody(false);
        defaultConfig.setLogResponseBody(false);
        defaultConfig.setMaxBodyLength(1024);
        defaultConfig.setLogLevel("INFO");
        defaultConfig.setLogFormat("[{timestamp}] {method} {path} - {status} ({duration}ms)");

        upgradeConfigObject(defaultConfig);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
                                            ServletFilterChain chain, LoggingConfig config) throws Exception {

        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();

        // 记录请求信息
        if (shouldLog(config.getLogLevel())) {
            logRequest(request, config);
        }

        try {
            // 继续执行过滤器链
            chain.doFilter(request, response);

            // 记录响应信息
            if (shouldLog(config.getLogLevel())) {
                long duration = System.currentTimeMillis() - startTime;
                logResponse(request, response, duration, config);
            }

        } catch (Exception e) {
            // 记录异常信息
            if (shouldLog(config.getLogLevel())) {
                long duration = System.currentTimeMillis() - startTime;
                logException(request, e, duration, config);
            }
            throw e;
        }
    }

    /**
     * 检查是否应该记录日志
     */
    private boolean shouldLog(String logLevel) {
        if ("OFF".equalsIgnoreCase(logLevel)) {
            return false;
        }

        // 简单的日志级别判断
        switch (logLevel.toUpperCase()) {
            case "ERROR":
                return log.isErrorEnabled();
            case "WARN":
                return log.isWarnEnabled();
            case "INFO":
                return log.isInfoEnabled();
            case "DEBUG":
                return log.isDebugEnabled();
            case "TRACE":
                return log.isTraceEnabled();
            default:
                return true;
        }
    }

    @Override
    protected boolean validateConfigObject(LoggingConfig config) {
        if (config == null) {
            log.error("配置对象不能为null");
            return false;
        }

        // 验证日志级别
        if (config.getLogLevel() != null) {
            String level = config.getLogLevel().toUpperCase();
            if (!level.matches("OFF|ERROR|WARN|INFO|DEBUG|TRACE")) {
                log.error("无效的日志级别: {}", level);
                return false;
            }
        }

        // 验证最大体长度
        if (config.getMaxBodyLength() < 0) {
            log.error("最大体长度不能为负数: {}", config.getMaxBodyLength());
            return false;
        }

        return true;
    }

    @Override
    protected void onConfigurationObjectChanged(LoggingConfig oldConfig, LoggingConfig newConfig) {
        log.info("日志过滤器配置更新:");
        log.info("  启用状态: {} -> {}",
                oldConfig != null ? oldConfig.isEnabled() : "null",
                newConfig != null ? newConfig.isEnabled() : "null");
        log.info("  日志级别: {} -> {}",
                oldConfig != null ? oldConfig.getLogLevel() : "null",
                newConfig != null ? newConfig.getLogLevel() : "null");
    }

    @Override
    public String getFilterName() {
        return "LoggingServletFilter";
    }

    @Override
    public int getOrder() {
        return 100; // 较低优先级，通常在其他过滤器之后执行
    }

    @Override
    public String getDescription() {
        LoggingConfig config = getConfigurationObject();
        return String.format("日志过滤器 (@version  %d) - 启用: %s, 级别: %s",
                getConfigVersion(),
                config != null ? config.isEnabled() : "unknown",
                config != null ? config.getLogLevel() : "unknown");
    }

    /**
     * 记录请求信息
     */
    private void logRequest(ServletRequest request, LoggingConfig config) {
        StringBuilder logMessage = new StringBuilder();

        // 基本请求信息
        String formattedMessage = formatLogMessage(config.getLogFormat(), request, null, 0, "REQUEST");
        logMessage.append(formattedMessage);

        // 记录请求头
        if (config.isLogRequestHeaders() && request.getHeaderNames() != null) {
            logMessage.append(" Headers: ");
            request.getHeaderNames().asIterator().forEachRemaining(headerName -> {
                String headerValue = request.getHeader(headerName);
                // 敏感信息脱敏
                if (isSensitiveHeader(headerName)) {
                    headerValue = maskSensitiveValue(headerValue);
                }
                logMessage.append(headerName).append("=").append(headerValue).append("; ");
            });
        }

        // 记录请求体
        if (config.isLogRequestBody() && request.getBody() != null && request.getBody().length > 0) {
            String body = request.getBodyAsString();
            if (body != null) {
                if (body.length() > config.getMaxBodyLength()) {
                    body = body.substring(0, config.getMaxBodyLength()) + "...";
                }
                logMessage.append(" Body: ").append(body);
            }
        }

        logWithLevel(config.getLogLevel(), logMessage.toString());
    }

    /**
     * 记录响应信息
     */
    private void logResponse(ServletRequest request, ServletResponse response, long duration, LoggingConfig config) {
        StringBuilder logMessage = new StringBuilder();

        // 基本响应信息
        String formattedMessage = formatLogMessage(config.getLogFormat(), request, response, duration, "RESPONSE");
        logMessage.append(formattedMessage);

        // 记录响应头
        if (config.isLogResponseHeaders() && response.getHeaders() != null) {
            logMessage.append(" Headers: ");
            for (String headerName : response.getHeaderNames()) {
                for (String headerValue : response.getHeaders(headerName)) {
                    logMessage.append(headerName).append("=").append(headerValue).append("; ");
                }
            }
        }

        // 记录响应体
        if (config.isLogResponseBody() && response.getBody() != null && response.getBody().length > 0) {
            String body = response.getBodyAsString();
            if (body != null) {
                if (body.length() > config.getMaxBodyLength()) {
                    body = body.substring(0, config.getMaxBodyLength()) + "...";
                }
                logMessage.append(" Body: ").append(body);
            }
        }

        logWithLevel(config.getLogLevel(), logMessage.toString());
    }

    /**
     * 记录异常信息
     */
    private void logException(ServletRequest request, Exception e, long duration, LoggingConfig config) {
        String formattedMessage = formatLogMessage(config.getLogFormat(), request, null, duration, "ERROR");
        String logMessage = formattedMessage + " Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage();

        log.error(logMessage, e);
    }
    /**
     * 格式化日志消息
     */
    private String formatLogMessage(String format, ServletRequest request, ServletResponse response, long duration, String type) {
        return format
                .replace("{timestamp}", String.valueOf(System.currentTimeMillis()))
                .replace("{method}", request.getMethod() != null ? request.getMethod() : "UNKNOWN")
                .replace("{path}", request.getPath() != null ? request.getPath() : "")
                .replace("{status}", response != null ? String.valueOf(response.getStatusCode()) : type)
                .replace("{duration}", String.valueOf(duration))
                .replace("{clientIp}", request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown")
                .replace("{userAgent}", request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "unknown");
    }

    /**
     * 根据级别记录日志
     */
    private void logWithLevel(String level, String message) {
        switch (level.toUpperCase()) {
            case "ERROR":
                log.error(message);
                break;
            case "WARN":
                log.warn(message);
                break;
            case "INFO":
                log.info(message);
                break;
            case "DEBUG":
                if (log.isDebugEnabled()) {
                    log.debug(message);
                }
                break;
            case "TRACE":
                log.trace(message);
                break;
            default:
                log.info(message);
                break;
        }
    }

    /**
     * 检查是否为敏感请求头
     */
    private boolean isSensitiveHeader(String headerName) {
        String lowerName = headerName.toLowerCase();
        return lowerName.contains("authorization") ||
               lowerName.contains("cookie") ||
               lowerName.contains("token") ||
               lowerName.contains("password");
    }

    /**
     * 检查是否为敏感参数
     */
    private boolean isSensitiveParam(String paramName) {
        String lowerName = paramName.toLowerCase();
        return lowerName.contains("password") ||
               lowerName.contains("token") ||
               lowerName.contains("secret") ||
               lowerName.contains("key");
    }

    /**
     * 脱敏敏感值
     */
    private String maskSensitiveValue(String value) {
        if (value == null || value.length() <= 4) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true;
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        
        // 基本配置
        options.add(FilterOption.builder()
            .name("启用状态")
            .key("enabled")
            .type(Boolean.class)
            .description("是否启用日志记录")
            .required(true)
            .defaultValue(true)
            .build());

        options.add(FilterOption.builder()
            .name("日志级别")
            .key("logLevel")
            .type(String.class)
            .description("日志记录级别：OFF, ERROR, WARN, INFO, DEBUG, TRACE")
            .required(true)
            .defaultValue("INFO")
            .validation("必须是有效的日志级别")
            .build());

        options.add(FilterOption.builder()
            .name("日志格式")
            .key("logFormat")
            .type(String.class)
            .description("日志格式模板，支持：{timestamp}, {method}, {path}, {status}, {duration}, {clientIp}, {userAgent}")
            .required(true)
            .defaultValue("[{timestamp}] {method} {path} - {status} ({duration}ms)")
            .build());

        // 请求响应配置
        options.add(FilterOption.builder()
            .name("记录请求头")
            .key("logRequestHeaders")
            .type(Boolean.class)
            .description("是否记录请求头信息")
            .defaultValue(true)
            .build());

        options.add(FilterOption.builder()
            .name("记录响应头")
            .key("logResponseHeaders")
            .type(Boolean.class)
            .description("是否记录响应头信息")
            .defaultValue(true)
            .build());

        options.add(FilterOption.builder()
            .name("记录请求体")
            .key("logRequestBody")
            .type(Boolean.class)
            .description("是否记录请求体内容")
            .defaultValue(false)
            .build());

        options.add(FilterOption.builder()
            .name("记录响应体")
            .key("logResponseBody")
            .type(Boolean.class)
            .description("是否记录响应体内容")
            .defaultValue(false)
            .build());

        options.add(FilterOption.builder()
            .name("最大体长度")
            .key("maxBodyLength")
            .type(Integer.class)
            .description("记录请求/响应体的最大长度")
            .defaultValue(1024)
            .validation("必须大于0")
            .build());
        
        return options;
    }

    /**
     * 日志配置类
     */
    @Data
    public static class LoggingConfig {
        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 是否记录请求头
         */
        private boolean logRequestHeaders = true;

        /**
         * 是否记录响应头
         */
        private boolean logResponseHeaders = true;

        /**
         * 是否记录请求体
         */
        private boolean logRequestBody = false;

        /**
         * 是否记录响应体
         */
        private boolean logResponseBody = false;

        /**
         * 是否记录请求参数
         */
        private boolean logRequestParams = true;

        /**
         * 最大体长度
         */
        private int maxBodyLength = 1024;

        /**
         * 日志级别
         */
        private String logLevel = "INFO";

        /**
         * 日志格式
         */
        private String logFormat = "[{timestamp}] {method} {path} - {status} ({duration}ms)";

        /**
         * 排除的请求头（小写）
         */
        private Set<String> excludeHeaders;

        /**
         * 排除的请求参数（小写）
         */
        private Set<String> excludeParams;
    }
}