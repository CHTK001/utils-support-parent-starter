package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

/**
 * 终结型过滤器 — 标记过滤器链的末端。
 *
 * <p>实现本接口的过滤器在默认情况下会直接结束响应，阻止链中后续过滤器的执行。
 * 需要自定义行为的实现类可覆写 {@link #doFilter}。</p>
 *
 * <p>{@link com.chua.common.support.network.server.filter.DefaultServerFilterChain}
 * 检测到该类型时，会在此 filter 执行完毕后终止链，不再继续后续 filter 和 handler。</p>
 *
 * @author CH
 * @since 1.0.0
 */
public interface EndServerFilter extends ServerFilter {

    /**
     * 终结响应，默认不继续传递到链的下一个过滤器。
     *
     * @param request  当前请求对象
     * @param response 当前响应对象
     * @param chain    过滤器链
     */
    @Override
    default void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        response.end();
    }

    /**
     * 终结型过滤器默认排序值最大，确保最后执行。
     *
     * @return 排序值
     */
    @Override
    default int getOrder() {
        return Integer.MAX_VALUE;
    }
}
