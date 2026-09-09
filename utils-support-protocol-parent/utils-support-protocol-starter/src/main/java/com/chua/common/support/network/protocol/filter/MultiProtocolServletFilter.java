package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 多协议支持过滤器示例
 * <p>
 * 演示如何创建支持多个特定协议的过滤器。
 * 该过滤器只会在指定的协议服务器中被初始化和执行。
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
public class MultiProtocolServletFilter  extends AbstractServletFilter implements ServletFilter {

    /**
     * 支持的协议集合
     */
    private static final Set<String> SUPPORTED_PROTOCOLS = new HashSet<>(Arrays.asList(
            "http", "https", "websocket"
    ));

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        log.info("多协议过滤器处理请求: {} (协议: {})", request.getPath(), getCurrentProtocol(request));
        
        // 根据不同协议添加不同的响应头
        String protocol = getCurrentProtocol(request);
        response.addHeader("X-Protocol", protocol);
        response.addHeader("X-Filter", "MultiProtocolFilter");
        
        if ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol)) {
            response.addHeader("X-HTTP-Version", "1.1");
        } else if ("websocket".equalsIgnoreCase(protocol)) {
            response.addHeader("X-WebSocket-Version", "13");
        }
        
        // 继续执行过滤器链
        chain.doFilter(request, response);
    }

    @Override
    public String getFilterName() {
        return "MultiProtocolFilter";
    }

    @Override
    public int getOrder() {
        return 40; // 较高优先级
    }

    @Override
    public String getDescription() {
        return "多协议支持过滤器，支持HTTP、HTTPS、WebSocket协议";
    }

    @Override
    public boolean supportProtocol(String protocol) {
        // 支持多个指定的协议（不区分大小写）
        return protocol != null && SUPPORTED_PROTOCOLS.contains(protocol.toLowerCase());
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.HTTPS, ProtocolType.WEBSOCKET};
    }

    /**
     * 从请求中获取当前协议
     * 
     * @param request 请求对象
     * @return 协议名称
     */
    private String getCurrentProtocol(ServletRequest request) {
        // 这里可以从请求中获取协议信息，或者从其他地方获取
        // 简单示例，实际实现可能需要更复杂的逻辑
        String scheme = request.getScheme();
        if (scheme != null) {
            return scheme.toLowerCase();
        }
        return "unknown";
    }
}
