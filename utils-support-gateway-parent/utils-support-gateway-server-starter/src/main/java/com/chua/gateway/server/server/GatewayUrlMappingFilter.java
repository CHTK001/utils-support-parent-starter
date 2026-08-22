package com.chua.gateway.server.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.DefaultObjectContext;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义 URL 映射过滤器（绕过 common-starter 的 UrlMappingServerFilter）。
 *
 * <p>直接维护 {@code Map<path, Map<HttpMethod, ServerHandler>>}，
 * 在 {@link #doFilter} 内精准匹配 path + method，避开 common-starter
 * ServerHandlerFactory 复杂的 pattern/lock/bean 解析路径。</p>
 *
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayUrlMappingFilter extends UrlMappingServerFilter {

    /**
     * 路由表：path -> method -> handler
     */
    private final Map<String, Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>>> routes =
            new ConcurrentHashMap<>();

    /**
     * 注册一条路由（任意 method，调用方自己处理 method 差异）。
     *
     * @param path    路径
     * @param handler 处理函数
     * @return 当前过滤器（链式调用）
     */
    public GatewayUrlMappingFilter route(String path,
                                          java.util.function.BiConsumer<ServerRequest, ServerResponse> handler) {
        Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>> map =
                routes.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        for (HttpMethod m : HttpMethod.values()) {
            map.put(m, handler);
        }
        log.info("[gateway-server] anyMethod route: {}", path);
        return this;
    }

    /**
     * 注册一条路由（指定 method）。
     *
     * @param path    路径
     * @param method  HTTP method
     * @param handler 处理函数
     * @return 当前过滤器（链式调用）
     */
    public GatewayUrlMappingFilter route(String path, HttpMethod method,
                                          java.util.function.BiConsumer<ServerRequest, ServerResponse> handler) {
        Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>> map =
                routes.computeIfAbsent(path, k -> new ConcurrentHashMap<>());
        map.put(method, handler);
        log.info("[gateway-server] route: {} {}", method, path);
        return this;
    }

    /**
     * 已注册路由总数（path 数）。
     *
     * @return path 数
     */
    public int routeCount() {
        return routes.size();
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        log.info("[gateway-debug] doFilter: path=[{}] method=[{}]", request.getPath(), request.getMethod());
        String path = request.getPath();
        HttpMethod method = request.getMethod();
        Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>> map = routes.get(path);
        if (map != null) {
            java.util.function.BiConsumer<ServerRequest, ServerResponse> handler = map.get(method);
            if (handler != null) {
                try {
                    handler.accept(request, response);
                    return;
                } catch (Exception e) {
                    log.warn("[gateway-server] handler 异常: path={} method={} msg={}",
                            path, method, e.getMessage());
                    throw e;
                }
            }
        }
        log.info("[gateway-debug] NO MATCH path=[{}] method=[{}]", path, method);
        // 未匹配 → 调用 chain 走其他 filter（如没有则 404）
        chain.doFilter(request, response);
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        // 高优先级（在大多数业务 filter 之前）
        return Integer.MAX_VALUE - 1000;
    }

    /**
     * 列出全部已注册路由（path → {method → handler}）。
     *
     * @return 不可变视图
     */
    public Map<String, Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>>> getRoutes() {
        Map<String, Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>>> snapshot =
                new LinkedHashMap<>();
        for (Map.Entry<String, Map<HttpMethod, java.util.function.BiConsumer<ServerRequest, ServerResponse>>> e : routes.entrySet()) {
            snapshot.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        return snapshot;
    }
}
