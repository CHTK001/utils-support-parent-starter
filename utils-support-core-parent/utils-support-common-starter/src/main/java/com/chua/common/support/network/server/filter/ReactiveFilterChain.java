package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.CompletionStage;

/**
* 响应式过滤器链，按顺序异步执行所有匹配的过滤器，最终调用目标处理器。
*
* <p>每个 {@link ReactiveServerFilter} 通过调用 {@link #doFilter} 放行。
* 过滤器内部可对返回的 {@link CompletionStage} 附加回调，实现<b>后置处理</b>。</p>
*
* @author CH
* @since 2026/07/16
 */
public interface ReactiveFilterChain {

    /**
    * 执行响应式过滤器链。
    *
    * @param request  请求对象
    * @param response 响应对象
    * @return 链执行完成的阶段
     */
    CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response);
}
