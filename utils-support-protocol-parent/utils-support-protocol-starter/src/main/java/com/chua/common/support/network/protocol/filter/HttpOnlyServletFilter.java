package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * HTTP协议专用过滤器示例
 * <p>
 * 演示如何创建只在HTTP协议下生效的过滤器。
 * 该过滤器只会在HTTP协议的服务器中被初始化和执行。
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
public class HttpOnlyServletFilter  extends AbstractServletFilter implements ServletFilter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        log.info("HTTP专用过滤器处理请求: {}", request.getPath());
        
        // 添加HTTP特定的响应头
        response.addHeader("X-Protocol", "HTTP");
        response.addHeader("X-Filter", "HttpOnlyFilter");
        
        // 继续执行过滤器链
        chain.doFilter(request, response);
    }

    @Override
    public String getFilterName() {
        return "HttpOnlyFilter";
    }

    @Override
    public int getOrder() {
        return 50; // 中等优先级
    }

    @Override
    public String getDescription() {
        return "HTTP协议专用过滤器，只在HTTP协议下生效";
    }

    @Override
    public boolean supportProtocol(String protocol) {
        // 只支持HTTP协议（不区分大小写）
        return "http".equalsIgnoreCase(protocol);
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }
}
