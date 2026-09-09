package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * WebSocket协议专用过滤器示例
 * <p>
 * 演示如何创建只在WebSocket协议下生效的过滤器。
 * 该过滤器只会在WebSocket协议的服务器中被初始化和执行。
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
@Spi("websocket")
@SpiDescribe("WebSocket协议专用过滤器")
@SpiSupport("websocket")
public class WebSocketOnlyServletFilter  extends AbstractServletFilter implements ServletFilter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        log.info("WebSocket专用过滤器处理请求: {}", request.getPath());
        
        // 添加WebSocket特定的响应头
        response.addHeader("X-Protocol", "WebSocket");
        response.addHeader("X-Filter", "WebSocketOnlyFilter");
        response.addHeader("X-WebSocket-Version", "13");
        
        // 继续执行过滤器链
        chain.doFilter(request, response);
    }

    @Override
    public String getFilterName() {
        return "WebSocketOnlyFilter";
    }

    @Override
    public int getOrder() {
        return 60; // 中等优先级
    }

    @Override
    public String getDescription() {
        return "WebSocket协议专用过滤器，只在WebSocket协议下生效";
    }

    @Override
    public boolean supportProtocol(String protocol) {
        // 只支持WebSocket协议（不区分大小写）
        return "websocket".equalsIgnoreCase(protocol);
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.WEBSOCKET};
    }
}
