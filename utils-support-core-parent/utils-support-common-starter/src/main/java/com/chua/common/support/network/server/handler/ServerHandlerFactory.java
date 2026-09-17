package com.chua.common.support.network.server.handler;

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

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    * 按 HTTP 方法区分的路由表（路径 -> 方法 -> 处理器）
    */
    private final Map<String, Map<HttpMethod, ServerHandler>> routes = new LinkedHashMap<>();

    /**
    * 不区分 HTTP 方法的路由表（路径 -> 处理器）
    */
    private final Map<String, ServerHandler> anyMethodRoutes = new LinkedHashMap<>();

    /**
    * 路由表读写锁
    */
    private final ReentrantReadWriteLock routeLock = new ReentrantReadWriteLock();

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
        Map<String, T> parsers = new LinkedHashMap<>(ServiceProvider.of(parserType).list());
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
        routeLock.writeLock().lock();
        try {
            routes.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(method, handler);
        } finally {
            routeLock.writeLock().unlock();
        }
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
        routeLock.writeLock().lock();
        try {
            anyMethodRoutes.put(path, handler);
        } finally {
            routeLock.writeLock().unlock();
        }
        return this;
    }

    /**
    * 批量注册路由。
    *
    * @param routes 路由映射表
    * @return 当前工厂实例
    */
    public ServerHandlerFactory<T> routes(LinkedHashMap<String, ServerHandler> routes) {
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
        if (path == null || path.isBlank()) {
            return this;
        }
        routeLock.writeLock().lock();
        try {
            routes.remove(path);
            anyMethodRoutes.remove(path);
        } finally {
            routeLock.writeLock().unlock();
        }
        return this;
    }

    /**
    * 获取路由总数。
    *
    * @return 路由总数
    */
    public int routeCount() {
        routeLock.readLock().lock();
        try {
            return routes.size() + anyMethodRoutes.size();
        } finally {
            routeLock.readLock().unlock();
        }
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
        routeLock.readLock().lock();
        try {
            return resolveHandlerLocked(path, method, request);
        } finally {
            routeLock.readLock().unlock();
        }
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
        if (path == null || path.isBlank()) {
            return null;
        }
        routeLock.readLock().lock();
        try {
            ServerHandler handler = anyMethodRoutes.get(path);
            if (handler != null) {
                return handler;
            }
            for (Map.Entry<String, ServerHandler> entry : anyMethodRoutes.entrySet()) {
                String pattern = entry.getKey();
                if (!pathMatcher.isPattern(pattern) || !pathMatcher.match(pattern, path)) {
                    continue;
                }
                return entry.getValue();
            }
            return null;
        } finally {
            routeLock.readLock().unlock();
        }
    }

    /**
    * 加锁状态下根据路径和方法匹配处理器。
    *
    * @param path    请求路径
    * @param method  HTTP 方法
    * @param request 请求对象
    * @return 匹配的处理器，未匹配返回 null
    */
    private ServerHandler resolveHandlerLocked(String path, HttpMethod method, ServerRequest request) {
        Map<HttpMethod, ServerHandler> methodMap = routes.get(path);
        if (methodMap != null) {
            ServerHandler handler = methodMap.get(method);
            if (handler != null) {
                return handler;
            }
        }
        ServerHandler anyMethodHandler = anyMethodRoutes.get(path);
        if (anyMethodHandler != null) {
            return anyMethodHandler;
        }
        for (Map.Entry<String, Map<HttpMethod, ServerHandler>> entry : routes.entrySet()) {
            String pattern = entry.getKey();
            if (!pathMatcher.isPattern(pattern) || !pathMatcher.match(pattern, path)) {
                continue;
            }
            methodMap = entry.getValue();
            ServerHandler handler = methodMap.get(method);
            if (handler == null) {
                handler = anyMethodRoutes.get(pattern);
            }
            if (handler != null) {
                setPathAttributes(request, pattern, path);
                return handler;
            }
        }
        for (Map.Entry<String, ServerHandler> entry : anyMethodRoutes.entrySet()) {
            String pattern = entry.getKey();
            if (!pathMatcher.isPattern(pattern) || !pathMatcher.match(pattern, path)) {
                continue;
            }
            setPathAttributes(request, pattern, path);
            return entry.getValue();
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
        if (path == null || path.isBlank() || !path.startsWith("/")) {
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
        routeLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        } finally {
            routeLock.readLock().unlock();
        }
    }

    /**
    * 获取所有不区分 HTTP 方法的路由表快照。
    *
    * @return 只读路由表
    */
    public Map<String, ServerHandler> getAnyMethodRoutes() {
        routeLock.readLock().lock();
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(anyMethodRoutes));
        } finally {
            routeLock.readLock().unlock();
        }
    }
}
