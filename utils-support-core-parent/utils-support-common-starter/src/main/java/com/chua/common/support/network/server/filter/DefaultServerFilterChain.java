package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.List;

/**
 * 默认过滤器链实现。
 *
 * <p>按顺序执行所有匹配的过滤器。支持 {@link FilterChainListener}，
 * 在每次 filter 执行前后触发回调。</p>
 *
 * <p>过滤器类型区分：</p>
 * <ul>
 *   <li><b>Access Filter</b>（{@code supportPath() == null}）— 每次请求都触发</li>
 *   <li><b>Endpoint Filter</b>（{@code supportPath() != null}）— 仅匹配路径时触发</li>
 *   <li><b>EndServerFilter</b> — 执行后终止链</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public class DefaultServerFilterChain implements ServerFilterChain {

    /**
     * Filters
    */
    private final List<ServerFilter> filters;
    /**
     * 处理器
    */
    private final ServerHandler handler;
    /**
     * Listeners
    */
    private final List<FilterChainListener> listeners;
    /**
     * 索引名
     */
    private int index;

    /**
     * 创建 DefaultServerFilterChain 实例
     * @param filters filters
     * @param ServerHandler ServerHandler
     * @param handler 处理器，不允许为 null
     */
    public DefaultServerFilterChain(List<ServerFilter> filters, ServerHandler handler) {
        this(filters, handler, null);
    }

    /**
     * 创建 DefaultServerFilterChain 实例
     * @param filters filters
     * @param ServerHandler ServerHandler
     * @param List List
     * @param listeners listeners
     * @param handler 处理器，不允许为 null
     */
    public DefaultServerFilterChain(List<ServerFilter> filters, ServerHandler handler, List<FilterChainListener> listeners) {
        this.filters = filters;
        this.handler = handler;
        this.listeners = listeners;
        this.index = 0;
    }

    @Override
    /**
     * Do过滤
    */
    public void doFilter(ServerRequest request, ServerResponse response) throws Exception {
        if (index < filters.size()) {
            ServerFilter filter = filters.get(index++);

            // 路径匹配检查：Endpoint Filter 仅在路径匹配时执行
            String path = filter.supportPath();
            if (path != null && !matchPath(path, request.getPath())) {
                // 路径不匹配，跳过此 filter
                if (response.isEnded()) {
                    return;
                }
                doFilter(request, response);
                return;
            }

            notifyBefore(filter, request, response);
            long start = System.nanoTime();
            try {
                filter.doFilter(request, response, this);
            } finally {
                long elapsed = System.nanoTime() - start;
                notifyAfter(filter, elapsed, request, response);
            }
        } else if (handler != null && !response.isEnded()) {
            handler.handle(request, response);
        }
    }

    /**
     * 路径匹配。
     *
     * @param pattern   过滤器绑定的路径模式
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

    /**
     * 通知Before
     * @param filter 过滤，不允许为 null
     * @param request 请求，不允许为 null
     * @param response 响应，不允许为 null
     */
    private void notifyBefore(ServerFilter filter, ServerRequest request, ServerResponse response) {
        List<FilterChainListener> currentListeners = getListeners(request);
        if (currentListeners == null) {
            return;
        }
        for (FilterChainListener l : currentListeners) {
            try {
                l.beforeFilter(filter, request, response);
            } catch (Exception ignored) {
                // NOTHING
            }
        }
    }

    /**
     * 通知After
     * @param filter 过滤，不允许为 null
     * @param elapsed 方法入参 elapsed
     * @param request 请求，不允许为 null
     * @param response 响应，不允许为 null
     */
    private void notifyAfter(ServerFilter filter, long elapsed, ServerRequest request, ServerResponse response) {
        List<FilterChainListener> currentListeners = getListeners(request);
        if (currentListeners == null) {
            return;
        }
        for (FilterChainListener l : currentListeners) {
            try {
                l.afterFilter(filter, elapsed, request, response);
            } catch (Exception ignored) {
                // NOTHING
            }
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * 获取Listeners
     * @param request 请求，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private List<FilterChainListener> getListeners(ServerRequest request) {
        Object value = request.getAttribute("_chainListeners");
        if (value instanceof List<?>) {
            return (List<FilterChainListener>) value;
        }
        return listeners;
    }
}
