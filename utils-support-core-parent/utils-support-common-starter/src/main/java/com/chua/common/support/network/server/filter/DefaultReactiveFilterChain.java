package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.handler.ReactiveServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 默认响应式过滤器链实现。
 *
 * <p>按顺序异步执行所有匹配的过滤器，最终调用目标处理器。
 * 每个 {@link ReactiveServerFilter} 通过调用 {@link ReactiveFilterChain#doFilter} 放行。</p>
 *
 * <p>过滤器执行逻辑：
 * <ol>
 *   <li>路径匹配检查：{@link ReactiveServerFilter#supportPath()} 不为空时，
 *       仅当请求路径匹配时才执行该过滤器</li>
 *   <li>执行过滤器 {@link ReactiveServerFilter#doFilter} 并等待其完成</li>
 *   <li>全部过滤器执行完毕后，调用目标处理器 {@link ServerHandler#handle}</li>
 * </ol></p>
 *
 * <p>注意：本实现为简化版，未引入 {@link FilterChainListener} 回调，
 * 因为响应式场景下 listener 可通过 {@link ReactiveServerFilter} 自行实现。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class DefaultReactiveFilterChain implements ReactiveFilterChain {

    /** 响应式过滤器列表 */
    private final List<ReactiveServerFilter> filters;

    /** 目标处理器（过滤器链末端） */
    private final ServerHandler handler;

    /** 当前执行索引 */
    /**
     * 索引名
     */
    private int index;

    /**
     * 构造响应式过滤器链。
     *
     * @param filters 过滤器列表
     * @param handler 目标处理器
     */
    public DefaultReactiveFilterChain(List<ReactiveServerFilter> filters, ServerHandler handler) {
        this.filters = filters != null ? filters : List.of();
        this.handler = handler;
        this.index = 0;
    }

    @Override
    /** Do过滤 */
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response) {
        // 全部过滤器已执行完，调用目标处理器
        if (index >= filters.size()) {
            if (handler != null && !response.isEnded()) {
                try {
                    if (handler instanceof ReactiveServerHandler reactive) {
                        return reactive.handleReactive(request, response);
                    }
                    // 同步执行处理器，避免与 whenComplete 竞争
                    handler.handle(request, response);
                    return CompletableFuture.completedStage(null);
                } catch (Exception e) {
                    log.warn("响应式处理器执行异常: {}", e.getMessage(), e);
                    if (!response.isEnded()) {
                        try {
                            response.sendError(500, "Internal Server Error");
                        } catch (Exception ex) {
                            log.warn("发送 500 错误失败: {}", ex.getMessage(), ex);
                        }
                    }
                }
            }
            return CompletableFuture.completedStage(null);
        }

        ReactiveServerFilter filter = filters.get(index++);

        // 路径匹配检查：Endpoint Filter 仅在路径匹配时执行
        String path = filter.supportPath();
        if (path != null && !matchPath(path, request.getPath())) {
            // 路径不匹配，跳过此 filter 继续下一个
            if (response.isEnded()) {
                return CompletableFuture.completedStage(null);
            }
            return doFilter(request, response);
        }

        try {
            CompletionStage<Void> stage = filter.doFilter(request, response, this);
            if (stage == null) {
                return CompletableFuture.completedStage(null);
            }
            return stage;
        } catch (Exception e) {
            log.warn("响应式过滤器执行异常: {}", filter.getClass().getSimpleName(), e);
            if (!response.isEnded()) {
                try {
                    response.sendError(500, "Internal Server Error");
                } catch (Exception ex) {
                    log.warn("发送 500 错误失败: {}", ex.getMessage(), ex);
                }
            }
            return CompletableFuture.completedStage(null);
        }
    }

    /**
     * 路径匹配。
     *
     * <p>支持精确匹配、前缀匹配和单级匹配。</p>
     *
     * @param pattern     过滤器绑定的路径模式
     * @param requestPath 请求路径
     * @return 匹配返回 true
     */
    private boolean matchPath(String pattern, String requestPath) {
        if (requestPath == null) {
            return false;
        }
        if (pattern.equals(requestPath)) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            String base = pattern.substring(0, pattern.length() - 3);
            return requestPath.startsWith(base);
        }
        if (pattern.endsWith("/*")) {
            String base = pattern.substring(0, pattern.length() - 2);
            return requestPath.startsWith(base) && requestPath.indexOf('/', base.length()) < 0;
        }
        return requestPath.startsWith(pattern + "/");
    }
}
