package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

/**
 * 过滤器链，按顺序执行所有匹配的过滤器，最终调用目标处理器。
 *
 * <p>单次请求使用，不可复用。每个 Filter 调用 {@link #doFilter} 放行。
 * Filter 调用 {@link ServerResponse#end()} 后，链检测到
 * {@link ServerResponse#isCommitted()} 为 true 即停止。
 *
 * @author CH
 * @since 2026/07/16
*/
@FunctionalInterface
public interface ServerFilterChain {

    /**
    * 执行过滤器链。
    *
    * <p>Filter 放行时调用，本方法内部检查 committed 状态决定是否继续。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @throws Exception 处理异常
    */
    void doFilter(ServerRequest request, ServerResponse response) throws Exception;
}
