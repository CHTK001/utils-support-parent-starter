package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.jspecify.annotations.NullUnmarked;

/**
 * 过滤器链执行监听器。
 *
 * <p>在 {@link DefaultServerFilterChain} 中每个 filter 执行前后触发，
 * 用于实现追踪、指标收集、审计日志等跨切面能力。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
public interface FilterChainListener {

    /**
     * filter 执行前回调。
     *
     * @param filter   将要执行的 filter
     * @param request  当前请求
     * @param response 当前响应
     */
    default void beforeFilter(ServerFilter filter, ServerRequest request, ServerResponse response) {
    }

    /**
     * filter 执行后回调。
     *
     * @param filter   已执行的 filter
     * @param elapsed  执行耗时（纳秒）
     * @param request  当前请求
     * @param response 当前响应
     */
    default void afterFilter(ServerFilter filter, long elapsed, ServerRequest request, ServerResponse response) {
    }
}
