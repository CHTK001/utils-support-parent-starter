package com.chua.common.support.network.protocol.proxy;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.filter.ServletFilter;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.core.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 代理响应处理过滤器
 * <p>
 * 用于对 HTTP 代理的返回值进行二次处理，支持：
 * 1. 通过 SPI (ServiceProvider) 加载 ProxyResponseHandler 实现
 * 2. 支持多个处理器链式调用
 * 3. 支持按路径/Content-Type 匹配处理
 *
 * @author CH
 * @since 2025/12/06
 */
@Slf4j
@Spi("proxy-response")
public class ProxyResponseServletFilter implements ServletFilter {

    private final String filterId = UUID.randomUUID().toString();

    /**
     * SPI 加载的处理器
     */
    private List<ProxyResponseHandler> handlers = new ArrayList<>();

    /**
     * 路径匹配模式
     */
    private Set<String> pathPatterns = new HashSet<>();

    /**
     * Content-Type 匹配
     */
    private Set<String> contentTypes = new HashSet<>();

    /**
     * 构造函数
     */
    public ProxyResponseServletFilter() {
        loadHandlers(null);
    }

    /**
     * 构造函数
     * @param handlerNames 处理器名称，逗号分隔
     */
    public ProxyResponseServletFilter(String handlerNames) {
        loadHandlers(handlerNames);
    }

    @Override
    public String getFilterId() {
        return filterId;
    }

    /**
     * 加载 SPI 处理器
     */
    private void loadHandlers(String handlerNames) {
        handlers = new ArrayList<>();

        // 通过 SPI 加载所有处理器
        ServiceProvider<ProxyResponseHandler> provider = ServiceProvider.of(ProxyResponseHandler.class);

        if (handlerNames == null || handlerNames.isEmpty()) {
            // 加载所有实现
            handlers.addAll(provider.list().values());
        } else {
            // 按名称加载指定处理器
            String[] names = handlerNames.split(",");
            for (String name : names) {
                String trimmedName = name.trim();
                if (!trimmedName.isEmpty()) {
                    ProxyResponseHandler handler = provider.getNewExtension(trimmedName);
                    if (handler != null) {
                        handlers.add(handler);
                    } else {
                        log.warn("未找到 ProxyResponseHandler: {}", trimmedName);
                    }
                }
            }
        }

        // 按优先级排序
        handlers.sort(Comparator.comparingInt(ProxyResponseHandler::getOrder));
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        // 检查是否需要处理
        if (!shouldProcess(request, response)) {
            chain.doFilter(request, response);
            return;
        }

        // 先执行后续过滤器（代理转发）
        chain.doFilter(request, response);

        // 对响应进行二次处理
        try {
            processResponse(request, response);
        } catch (Exception e) {
            log.error("处理代理响应失败", e);
            handleError(request, response, e);
        }
    }

    /**
     * 检查是否需要处理该请求
     */
    private boolean shouldProcess(ServletRequest request, ServletResponse response) {
        // 如果没有配置任何处理器，跳过
        if (handlers.isEmpty()) {
            return false;
        }

        // 路径匹配
        if (!pathPatterns.isEmpty()) {
            String path = request.getPath();
            boolean pathMatched = pathPatterns.stream()
                    .anyMatch(pattern -> matchPath(path, pattern));
            if (!pathMatched) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查响应 Content-Type 是否匹配
     */
    private boolean matchContentType(ServletResponse response) {
        if (contentTypes.isEmpty()) {
            return true;
        }

        String contentType = response.getContentType();
        if (contentType == null) {
            return false;
        }

        return contentTypes.stream()
                .anyMatch(pattern -> contentType.toLowerCase().contains(pattern.toLowerCase()));
    }

    /**
     * 路径匹配
     */
    private boolean matchPath(String path, String pattern) {
        if (pattern.equals("*") || pattern.equals("/**")) {
            return true;
        }

        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.startsWith(prefix);
        }

        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }

        if (pattern.contains("*")) {
            String regex = pattern.replace("*", ".*");
            return path.matches(regex);
        }

        return path.equals(pattern);
    }

    /**
     * 处理响应
     */
    private void processResponse(ServletRequest request, ServletResponse response) {
        // Content-Type 匹配
        if (!matchContentType(response)) {
            return;
        }

        // 执行 SPI 处理器
        for (ProxyResponseHandler handler : handlers) {
            try {
                if (handler.supports(request, response)) {
                    ServletResponse processed = handler.handle(request, response);
                    if (processed != null) {
                        copyResponse(processed, response);
                    }
                }
            } catch (Exception e) {
                log.error("执行 ProxyResponseHandler 失败: {}", handler.getName(), e);
                handler.handleError(request, response, e);
            }
        }

    }

    /**
     * 复制响应
     */
    private void copyResponse(ServletResponse source, ServletResponse target) {
        if (source.getBody() != null) {
            target.setBody(source.getBody());
        }
        if (source.getStatusCode() > 0) {
            target.setStatusCode(source.getStatusCode());
        }
        if (source.getContentType() != null) {
            target.setContentType(source.getContentType());
        }
        if (source.getHeaders() != null) {
            source.getHeaders().forEach((key, value) -> target.getHeaders().set(key, value));
        }
    }

    /**
     * 处理错误
     */
    private void handleError(ServletRequest request, ServletResponse response, Exception e) {
        for (ProxyResponseHandler handler : handlers) {
            try {
                handler.handleError(request, response, e);
            } catch (Exception ex) {
                log.error("处理错误失败: {}", handler.getName(), ex);
            }
        }
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS};
    }

    @Override
    public String getDescription() {
        return "代理响应处理过滤器 - 支持 SPI 处理器";
    }

    // ========== 动态配置方法 ==========

    /**
     * 添加处理器
     */
    public ProxyResponseServletFilter addHandler(ProxyResponseHandler handler) {
        handlers.add(handler);
        handlers.sort(Comparator.comparingInt(ProxyResponseHandler::getOrder));
        return this;
    }

    /**
     * 移除处理器
     */
    public ProxyResponseServletFilter removeHandler(String name) {
        handlers.removeIf(h -> h.getName().equals(name));
        return this;
    }

    /**
     * 添加路径匹配模式
     */
    public ProxyResponseServletFilter addPathPattern(String pattern) {
        pathPatterns.add(pattern);
        return this;
    }

    /**
     * 添加 Content-Type 匹配
     */
    public ProxyResponseServletFilter addContentType(String contentType) {
        contentTypes.add(contentType);
        return this;
    }

    /**
     * 获取已加载的处理器列表
     */
    public List<String> getHandlerNames() {
        return handlers.stream()
                .map(ProxyResponseHandler::getName)
                .collect(Collectors.toList());
    }
}
