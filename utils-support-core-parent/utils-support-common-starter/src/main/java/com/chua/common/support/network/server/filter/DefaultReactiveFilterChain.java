// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
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
 * <p>链路监听器：若请求上挂载了 {@code _chainListeners}（由 {@link TracingServerFilter}
 * 写入），每个过滤器执行前后会回调 {@link FilterChainListener}；未挂载时只多一次请求
 * 属性读取，不产生回调开销。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class DefaultReactiveFilterChain implements ReactiveFilterChain {

    /**
     * 过滤器链监听器在请求上的属性键（由 {@link TracingServerFilter} 写入）。
     */
    private static final String LISTENERS_KEY = "_chainListeners";

    /**
     * 共享的「已完成」信号。
     *
     * <p>同步过滤器与同步 handler 是常态，每个请求、每一步放行都会返回一个完成态；
     * 每次 {@code CompletableFuture.completedFuture(null)} 都要新分配一个对象，
     * 实测占全部申请的 8.5%（百万级 QPS 下是纯 GC 负担）。完成态不可再变，
     * 因此全进程共享一个实例即可：调用方只做 {@code whenComplete}/{@code join}，
     * 两者都不会修改该实例。</p>
     */
    private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

    /**
     * 响应式过滤器列表
     */
    private final List<ReactiveServerFilter> filters;

    /**
     * 目标处理器（过滤器链末端）
     */
    private final ServerHandler handler;

    /**
     * 当前执行索引
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

    /**
     * Do过滤
     */
    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response) {
        // 全部过滤器已执行完，调用目标处理器
        if (index >= filters.size()) {
            if (handler != null && !response.isEnded()) {
                try {
                    if (handler instanceof ReactiveServerHandler reactive) {
                        return reactive.handleReactive(request, response);
                    }
                    // 同步执行：避免与 whenComplete 竞争导致 404 状态丢失
                    handler.handle(request, response);
                    return COMPLETED;
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
            return COMPLETED;
        }

        ReactiveServerFilter filter = filters.get(index++);

        // 契约：过滤器停用后必须被跳过。同步 {@link ServerFilter} 直接判定 isEnabled()；
        // 过滤链列表被 ServerFilterManager 缓存，而「过滤器热启停」只改实例标志、不重建
        // 列表，因此必须逐请求判定，否则热切换形同虚设。响应式原生过滤器不参与该开关，
        // 视为恒启用（仅包装的同步过滤器与 DelegatingServerFilter 支持热启停）。
        if (filter instanceof ServerFilter syncFilter && !syncFilter.isEnabled()) {
            if (response.isEnded()) {
                return COMPLETED;
            }
            return doFilter(request, response);
        }

        // 路径匹配检查：Endpoint Filter 仅在路径匹配时执行
        String path = filter.supportPath();
        if (path != null && !matchPath(path, request.getPath())) {
            // 路径不匹配，跳过此 filter 继续下一个
            if (response.isEnded()) {
                return COMPLETED;
            }
            return doFilter(request, response);
        }

        try {
            List<FilterChainListener> listeners = listenersOf(request);
            long start = listeners != null ? System.nanoTime() : 0L;
            if (listeners != null) {
                notifyBefore(listeners, filter, request, response);
            }
            try {
                CompletionStage<Void> stage = filter.doFilter(request, response, this);
                if (stage == null) {
                    return COMPLETED;
                }
                return stage;
            } finally {
                if (listeners != null) {
                    notifyAfter(listeners, filter, System.nanoTime() - start, request, response);
                }
            }
        } catch (Exception e) {
            log.warn("响应式过滤器执行异常: {}", filter.getClass().getSimpleName(), e);
            if (!response.isEnded()) {
                try {
                    response.sendError(500, "Internal Server Error");
                } catch (Exception ex) {
                    log.warn("发送 500 错误失败: {}", ex.getMessage(), ex);
                }
            }
            return COMPLETED;
        }
    }

    /**
     * 读取当前请求挂载的链路监听器。
     *
     * <p>无监听器时返回 null：未开启链路追踪的请求在下游不会产生任何额外回调开销，
     * 只多一次请求属性读取。</p>
     *
     * @param request 当前请求
     * @return 监听器列表，未挂载时返回 null
     */
    @SuppressWarnings("unchecked")
    private static List<FilterChainListener> listenersOf(ServerRequest request) {
        Object value = request.getAttribute(LISTENERS_KEY);
        if (value instanceof List<?>) {
            return (List<FilterChainListener>) value;
        }
        return null;
    }

    /**
     * 通知 filter 执行前。
     *
     * @param listeners 监听器列表
     * @param filter    将要执行的 filter
     * @param request   当前请求
     * @param response  当前响应
     */
    private static void notifyBefore(List<FilterChainListener> listeners, ReactiveServerFilter filter,
                                     ServerRequest request, ServerResponse response) {
        // 链上的静态过滤器都被 ServerFilterManager 包成 StaticReactiveFilterAdapter(同时实现
        // ServerFilter)，原生响应式过滤器不参与回调：监听器契约以 ServerFilter 为标识。
        if (!(filter instanceof ServerFilter syncFilter)) {
            return;
        }
        for (FilterChainListener listener : listeners) {
            try {
                listener.beforeFilter(syncFilter, request, response);
            } catch (Exception ignored) {
                // 监听器异常与请求处理无关，忽略
            }
        }
    }

    /**
     * 通知 filter 执行后。
     *
     * @param listeners 监听器列表
     * @param filter    已执行的 filter
     * @param elapsed   执行耗时（纳秒）
     * @param request   当前请求
     * @param response  当前响应
     */
    private static void notifyAfter(List<FilterChainListener> listeners, ReactiveServerFilter filter, long elapsed,
                                    ServerRequest request, ServerResponse response) {
        if (!(filter instanceof ServerFilter syncFilter)) {
            return;
        }
        for (FilterChainListener listener : listeners) {
            try {
                listener.afterFilter(syncFilter, elapsed, request, response);
            } catch (Exception ignored) {
                // 同上
            }
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