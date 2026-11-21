package com.chua.common.support.network.server.handler;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.matcher.PathMatcher;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.http.HttpDefaultServerHandler;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;

/**
 * 服务器处理器工厂。
 *
 * <p>负责管理 URL 路由注册与匹配，并支持通过 SPI 发现
 * {@link ServerHandlerAnnotationParser} 实现来扫描注解 Bean。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class ServerHandlerFactory<T extends ServerHandlerAnnotationParser> {

    /**
     * Ant 风格路径匹配器
     */
    private final PathMatcher pathMatcher = PathMatcher.INSTANCE;

    /**
     * 对象上下文，用于访问 IOC 容器中的 Bean
     */
    private final ObjectContext objectContext;

    /**
     * 不可变路由表快照。
     *
     * <p>读路径（每请求路由解析）对快照无锁直读；写路径（启动期/运行期路由注册）
     * 复制-替换后原子换引用。路由解析是所有 Server 协议的每请求热路径，
     * 读写锁的读锁获取/释放是两次 CAS，快照读只多一次 volatile 读。</p>
     */
    private static final class RouteTable {

        /**
         * 空表（无任何路由）。
         */
        static final RouteTable EMPTY = new RouteTable(Collections.emptyMap(), Collections.emptyMap());

        /**
         * 按 HTTP 方法区分的路由表（路径 -> 方法 -> 处理器），不可变。
         */
        final Map<String, Map<HttpMethod, ServerHandler>> routes;

        /**
         * 不区分 HTTP 方法的路由表（路径 -> 处理器），不可变。
         */
        final Map<String, ServerHandler> anyMethodRoutes;

        /**
         * 含通配符/路径变量的模式路由（按注册顺序预分离）。
         *
         * <p>路由解析是每请求热路径：精确匹配未命中时旧实现要对全部路由 key 逐个做
         * {@code isPattern} 字符扫描（404 请求更是全表扫两遍）。快照构建期把模式路由
         * 预分离成独立列表后，请求期只扫描真正的模式路由——全精确路由的服务直接跳过扫描。</p>
         */
        final List<PatternMethodRoute> patternRoutes;

        /**
         * 不区分方法的模式路由（按注册顺序预分离），同 {@link #patternRoutes}。
         */
        final List<PatternAnyRoute> patternAnyMethodRoutes;

        /**
         * 构造不可变快照。
         *
         * @param routes          按方法区分的路由表（调用方保证不可变）
         * @param anyMethodRoutes 不区分方法的路由表（调用方保证不可变）
         */
        RouteTable(Map<String, Map<HttpMethod, ServerHandler>> routes, Map<String, ServerHandler> anyMethodRoutes) {
            this.routes = routes;
            this.anyMethodRoutes = anyMethodRoutes;
            List<PatternMethodRoute> patterns = new ArrayList<>();
            for (Map.Entry<String, Map<HttpMethod, ServerHandler>> entry : routes.entrySet()) {
                if (PathMatcher.INSTANCE.isPattern(entry.getKey())) {
                    patterns.add(new PatternMethodRoute(entry.getKey(), entry.getValue()));
                }
            }
            this.patternRoutes = Collections.unmodifiableList(patterns);
            List<PatternAnyRoute> anyPatterns = new ArrayList<>();
            for (Map.Entry<String, ServerHandler> entry : anyMethodRoutes.entrySet()) {
                if (PathMatcher.INSTANCE.isPattern(entry.getKey())) {
                    anyPatterns.add(new PatternAnyRoute(entry.getKey(), entry.getValue()));
                }
            }
            this.patternAnyMethodRoutes = Collections.unmodifiableList(anyPatterns);
        }
    }

    /**
     * 按方法区分的模式路由条目。
     *
     * @param pattern  路由模式
     * @param handlers 方法 -> 处理器
     */
    private record PatternMethodRoute(String pattern, Map<HttpMethod, ServerHandler> handlers) {
    }

    /**
     * 不区分方法的模式路由条目。
     *
     * @param pattern 路由模式
     * @param handler 处理器
     */
    private record PatternAnyRoute(String pattern, ServerHandler handler) {
    }

    /**
     * 当前路由表快照，volatile 保证读线程可见性与写入原子性。
     */
    private volatile RouteTable table = RouteTable.EMPTY;

    /**
     * 创建 ServerHandlerFactory 实例
     * @param objectContext objectContext
     */
    public ServerHandlerFactory(ObjectContext objectContext) {
        this.objectContext = objectContext;
    }

    /**
     * 根据接口类型通过 SPI 发现所有实现并执行解析。
     *
     * @param parserType             解析器接口类型
     * @param serverFilter 服务器过滤器
     */
    public void initialize(Class<T> parserType, ServerFilter serverFilter) {
        Map<String, T> parsers = Wraps.linkedMap(ServiceProvider.of(parserType).list());
        Map<String, T> iocParsers = objectContext.getBeanOfTypes(parserType);
        if (iocParsers != null) {
            parsers.putAll(iocParsers);
        }

        List<T> sorted = parsers.values().stream()
                .sorted(Comparator.comparingInt(ServerHandlerAnnotationParser::getPriority).reversed())
                .toList();

        for (T parser : sorted) {
            try {
                List<ServerHandler> parsed = parser.parse(objectContext, serverFilter);
                if (parsed != null) {
                    parsed.forEach(this::registerFromHandler);
                }
            } catch (Exception e) {
                log.warn("ServerHandlerAnnotationParser[{}] 解析失败", parser.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * 将处理器注册到路由表
     *
     * @param handler 处理器
     */
    private void registerFromHandler(ServerHandler handler) {
        if (handler instanceof HttpDefaultServerHandler httpHandler) {
            HttpMethod method = httpHandler.method();
            if (method != null) {
                route(httpHandler.path(), method, handler);
            } else {
                route(httpHandler.path(), handler);
            }
        }
    }

    /**
     * 注册指定 HTTP 方法的路由。
     *
     * @param path    请求路径
     * @param method  HTTP 方法
     * @param handler 处理器
     * @return 当前工厂实例
     */
    public ServerHandlerFactory<T> route(String path, HttpMethod method, ServerHandler handler) {
        validateRoute(path, handler);
        RouteTable cur = table;
        var routes = new LinkedHashMap<String, Map<HttpMethod, ServerHandler>>(cur.routes);
        Map<HttpMethod, ServerHandler> methodMap = new LinkedHashMap<>(
                cur.routes.getOrDefault(path, Collections.emptyMap()));
        methodMap.put(method, handler);
        routes.put(path, Collections.unmodifiableMap(methodMap));
        table = new RouteTable(Collections.unmodifiableMap(routes), cur.anyMethodRoutes);
        return this;
    }

    /**
     * 注册不区分 HTTP 方法的路由。
     *
     * @param path    请求路径
     * @param handler 处理器
     * @return 当前工厂实例
     */
    public ServerHandlerFactory<T> route(String path, ServerHandler handler) {
        validateRoute(path, handler);
        RouteTable cur = table;
        Map<String, ServerHandler> anyMethodRoutes = new LinkedHashMap<>(cur.anyMethodRoutes);
        anyMethodRoutes.put(path, handler);
        table = new RouteTable(cur.routes, Collections.unmodifiableMap(anyMethodRoutes));
        return this;
    }

    /**
     * 批量注册路由。
     *
     * @param routes 路由映射表
     * @return 当前工厂实例
     */
    public ServerHandlerFactory<T> routes(Map<String, ServerHandler> routes) {
        for (Map.Entry<String, ServerHandler> entry : routes.entrySet()) {
            route(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * 移除指定路径的路由。
     *
     * @param path 请求路径
     * @return 当前工厂实例
     */
    public ServerHandlerFactory<T> removeRoute(String path) {
        if (StringUtils.isBlank(path)) {
            return this;
        }
        RouteTable cur = table;
        Map<String, Map<HttpMethod, ServerHandler>> routes = new LinkedHashMap<>(cur.routes);
        routes.remove(path);
        Map<String, ServerHandler> anyMethodRoutes = new LinkedHashMap<>(cur.anyMethodRoutes);
        anyMethodRoutes.remove(path);
        table = new RouteTable(Collections.unmodifiableMap(routes), Collections.unmodifiableMap(anyMethodRoutes));
        return this;
    }

    /**
     * 获取路由总数。
     *
     * @return 路由总数
     */
    public int routeCount() {
        RouteTable cur = table;
        return cur.routes.size() + cur.anyMethodRoutes.size();
    }

    /**
     * 根据请求匹配处理器。
     *
     * @param request 请求
     * @return 匹配的处理器，未匹配返回 null
     */
    public ServerHandler resolveHandler(ServerRequest request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();
        RouteTable cur = table;
        return resolveHandlerIn(cur, path, method, request);
    }

    /**
     * 根据路径字符串直接匹配处理器（不区分 HTTP 方法）。
     * <p>
     * 适用于 IPC、Shell 等非 HTTP 协议的路由查找。</p>
     *
     * @param path 路径字符串
     * @return 匹配的处理器，未匹配返回 null
     */
    public ServerHandler resolveHandler(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        RouteTable cur = table;
        ServerHandler handler = cur.anyMethodRoutes.get(path);
        if (handler != null) {
            return handler;
        }
        for (PatternAnyRoute route : cur.patternAnyMethodRoutes) {
            if (!pathMatcher.match(route.pattern(), path)) {
                continue;
            }
            return route.handler();
        }
        return null;
    }

    /**
     * 在指定路由表快照内按路径和方法匹配处理器。
     *
     * @param t       路由表快照
     * @param path    请求路径
     * @param method  HTTP 方法
     * @param request 请求对象
     * @return 匹配的处理器，未匹配返回 null
     */
    private ServerHandler resolveHandlerIn(RouteTable t, String path, HttpMethod method, ServerRequest request) {
        Map<HttpMethod, ServerHandler> methodMap = t.routes.get(path);
        if (methodMap != null) {
            ServerHandler handler = methodMap.get(method);
            if (handler != null) {
                return handler;
            }
        }
        ServerHandler anyMethodHandler = t.anyMethodRoutes.get(path);
        if (anyMethodHandler != null) {
            return anyMethodHandler;
        }
        for (PatternMethodRoute route : t.patternRoutes) {
            String pattern = route.pattern();
            if (!pathMatcher.match(pattern, path)) {
                continue;
            }
            ServerHandler handler = route.handlers().get(method);
            if (handler == null) {
                handler = t.anyMethodRoutes.get(pattern);
            }
            if (handler != null) {
                setPathAttributes(request, pattern, path);
                return handler;
            }
        }
        for (PatternAnyRoute route : t.patternAnyMethodRoutes) {
            String pattern = route.pattern();
            if (!pathMatcher.match(pattern, path)) {
                continue;
            }
            setPathAttributes(request, pattern, path);
            return route.handler();
        }
        return null;
    }

    /**
     * 设置路径匹配属性和路径变量到请求对象。
     *
     * @param request 请求对象
     * @param pattern 匹配的路由模式
     * @param path    实际请求路径
     */
    private void setPathAttributes(ServerRequest request, String pattern, String path) {
        Map<String, String> variables = pathMatcher.extractUriTemplateVariables(pattern, path);
        ServerAttribute.setPathVariables(request, variables);
        ServerAttribute.setRoutePattern(request, pattern);
    }

    /**
     * 校验路由参数。
     *
     * @param path    请求路径
     * @param handler 处理器
     */
    private void validateRoute(String path, ServerHandler handler) {
        if (StringUtils.isBlank(path) || !path.startsWith("/")) {
            throw new IllegalArgumentException("路由路径必须以 / 开头");
        }
        if (handler == null) {
            throw new IllegalArgumentException("路由处理器不能为空");
        }
    }

    /**
     * 获取所有按 HTTP 方法区分的路由表快照。
     *
     * @return 只读路由表
     */
    public Map<String, Map<HttpMethod, ServerHandler>> getRoutes() {
        return table.routes;
    }

    /**
     * 获取所有不区分 HTTP 方法的路由表快照。
     *
     * @return 只读路由表
     */
    public Map<String, ServerHandler> getAnyMethodRoutes() {
        return table.anyMethodRoutes;
    }
}
