package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.handler.ServerHandlerFactory;
import com.chua.common.support.network.server.http.HttpReflectiveDefaultServerHandler;
import com.chua.common.support.network.server.handler.ReactiveServerHandler;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.ThreadUtils;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * URL 路径到处理器映射过滤器。
 *
 * <p>将 URL 路径映射到处理器。支持 Ant 风格模式匹配：
 * <ul>
 *   <li>{@code ?} — 匹配任意单个字符</li>
 *   <li>{@code *} — 匹配路径段中任意字符（不跨目录）</li>
 *   <li>{@code **} — 匹配任意路径（跨目录）</li>
 *   <li>{@code {var}} — 路径变量，匹配任意字符并捕获</li>
 *   <li>{@code {var:regex}} — 带正则约束的路径变量</li>
 * </ul>
 *
 * <p>匹配的 {var} 变量会自动设置为请求属性，可通过 {@link ServerRequest#getAttribute(String)} 获取。
 * 未匹配时放行到下一个 Filter。</p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi("url-mapping")
@SpiDescribe("URL 路径到处理器映射过滤器")
@SuppressWarnings("NullAway")
@NullUnmarked
public class UrlMappingServerFilter implements EndServerFilter, ReactiveServerFilter {

    /**
     * 处理器工厂，管理路由注册与匹配
     */
    private final ServerHandlerFactory<ServerHandlerAnnotationParser> factory;

    /**
     * 默认排序值
     */
    private static final int FILTER_ORDER = Integer.MAX_VALUE - 100;

    /**
     * 过滤器标识
     */
    private static final String FILTER_ID = "UrlMappingServerFilter";

    /**
     * 构造并传入 {@link ObjectContext}，自动扫描所有注解解析器注册路由。
     *
     * @param objectContext 对象上下文
     */
    public UrlMappingServerFilter(ObjectContext objectContext) {
        this.factory = new ServerHandlerFactory<>(objectContext);
        this.factory.initialize(ServerHandlerAnnotationParser.class, this);
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        ServerHandler handler = factory.resolveHandler(request);
        if (handler != null) {
            handler.handle(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response, ReactiveFilterChain chain) {
        ServerHandler handler = factory.resolveHandler(request);
        if (handler instanceof ReactiveServerHandler reactive) {
            return reactive.handleReactive(request, response);
        }
        if (handler != null) {
            return CompletableFuture.runAsync(new Runnable() {
                @Override
                public void run() {
                    try {
                        handler.handle(request, response);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }, ThreadUtils.GLOBAL_EXECUTOR);
        }
        return chain.doFilter(request, response);
    }

    /**
     * 注册指定 HTTP 方法的路由。
     *
     * @param path    请求路径
     * @param method  HTTP 方法
     * @param handler 处理器
     * @return 当前过滤器实例
     */
    public UrlMappingServerFilter route(String path, HttpMethod method, ServerHandler handler) {
        factory.route(path, method, handler);
        return this;
    }

    /**
     * 注册不区分 HTTP 方法的路由。
     *
     * @param path    请求路径
     * @param handler 处理器
     * @return 当前过滤器实例
     */
    public UrlMappingServerFilter route(String path, ServerHandler handler) {
        factory.route(path, handler);
        return this;
    }

    /**
     * 批量注册路由。
     *
     * @param routes 路由映射表
     * @return 当前过滤器实例
     */
    public UrlMappingServerFilter routes(LinkedHashMap<String, ServerHandler> routes) {
        factory.routes(routes);
        return this;
    }

    /**
     * 移除指定路径的路由。
     *
     * @param path 请求路径
     * @return 当前过滤器实例
     */
    public UrlMappingServerFilter removeRoute(String path) {
        factory.removeRoute(path);
        return this;
    }

    /**
     * 获取路由总数。
     *
     * @return 路由总数
     */
    public int routeCount() {
        return factory.routeCount();
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public String getFilterId() {
        return FILTER_ID;
    }

    @Override
    public String supportPath() {
        return null;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.WS};
    }
}