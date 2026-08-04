package com.chua.common.support.network.server.handler;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.NullUnmarked;

/**
 * 响应式请求处理器接口。
 *
 * <p>与同步 {@link ServerHandler} 不同，
 * 本接口的 {@link #handle} 返回 {@link CompletionStage}，调用方无需阻塞等待处理结果。
 * 适用于 Netty / Vert.x 等事件循环模型下的非阻塞 I/O。</p>
 *
 * <p>当处理器实现了本接口时，{@link com.chua.common.support.network.server.filter.UrlMappingServerFilter}
 * 会优先调用其异步路径。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
@FunctionalInterface
public interface ReactiveServerHandler {

    /**
     * 异步处理请求。
     *
     * @param request  请求对象
     * @param response 响应对象
     * @return 完成时表示请求处理结束；失败时触发链的错误传播
     */
    CompletionStage<Void> handleReactive(ServerRequest request, ServerResponse response);
}
