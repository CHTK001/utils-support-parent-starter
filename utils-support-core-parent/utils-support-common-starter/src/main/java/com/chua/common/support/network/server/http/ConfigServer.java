package com.chua.common.support.network.server.http;

import com.chua.common.support.network.annotations.ResponseConverter;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.annotations.RequestMethod;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.ServiceProvider;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * HTTP 协议专用服务器接口，继承自协议无关的 {@link Server}。
 *
 * <p>提供 HTTP 路由注册、HTTP 方法快捷调用、IOC Handler 扫描等专有操作。
 * HTTP 服务器实现类应实现此接口而非直接实现 {@link Server}。
 *
 * <h2>路由注册</h2>
 * <pre>{@code
 * ConfigServer server = new NettyHttpServer(setting);
 * server.registerMapping("/api/users", HttpMethod.GET, handler);
 * server.registerMapping("/api/users", HttpMethod.POST, createHandler);
 * server.post("/api/login", loginHandler);
 * }</pre>
 *
 * <h2>HTTP 方法快捷注册</h2>
 * <ul>
 *   <li>{@link #get(String, ServerHandler)} — GET 方法快捷注册</li>
 *   <li>{@link #post(String, ServerHandler)} — POST 方法快捷注册</li>
 *   <li>{@link #put(String, ServerHandler)} — PUT 方法快捷注册</li>
 *   <li>{@link #delete(String, ServerHandler)} — DELETE 方法快捷注册</li>
 *   <li>{@link #patch(String, ServerHandler)} — PATCH 方法快捷注册</li>
 * </ul>
 *
 * @author CH
 * @version 2.0
 * @since 2026/07/16
 */
public interface ConfigServer extends Server {

    // ==================== 路由注册 ====================

    /**
     * 注册指定 HTTP 方法的路径映射。
     *
     * @param path    请求路径，如 "/api/users"
     * @param method  HTTP 方法
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    Server registerMapping(String path, HttpMethod method, ServerHandler handler);

    /**
     * 注册不区分 HTTP 方法的路径映射（匹配所有方法）。
     *
     * @param path    请求路径
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    Server registerMapping(String path, ServerHandler handler);

    /**
     * 移除指定路径的路由映射。
     *
     * @param path 请求路径
     * @return 当前服务器实例，支持链式调用
     */
    Server removeMapping(String path);

    // ==================== HTTP 方法快捷注册 ====================

    /**
     * 注册 GET 方法路径映射。
     *
     * @param path    请求路径
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    default Server get(String path, ServerHandler handler) {
        return registerMapping(path, HttpMethod.GET, handler);
    }

    /**
     * 注册 POST 方法路径映射。
     *
     * @param path    请求路径
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    default Server post(String path, ServerHandler handler) {
        return registerMapping(path, HttpMethod.POST, handler);
    }

    /**
     * 注册 PUT 方法路径映射。
     *
     * @param path    请求路径
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    default Server put(String path, ServerHandler handler) {
        return registerMapping(path, HttpMethod.PUT, handler);
    }

    /**
     * 注册 DELETE 方法路径映射。
     *
     * @param path    请求路径
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    default Server delete(String path, ServerHandler handler) {
        return registerMapping(path, HttpMethod.DELETE, handler);
    }

    /**
     * 注册 PATCH 方法路径映射。
     *
     * @param path    请求路径
     * @param handler 请求处理器
     * @return 当前服务器实例，支持链式调用
     */
    default Server patch(String path, ServerHandler handler) {
        return registerMapping(path, HttpMethod.PATCH, handler);
    }

    // ==================== 对象注册与路由映射 ====================

    /**
     * 注册一个普通对象，通过注解解析自动生成路由映射。
     *
     * <p>将对象注册到 IOC 容器后，扫描类级和方法级的 {@link RequestMethod} 注解，
     * 生成 {@link com.chua.common.support.network.server.filter.UrlMappingServerFilter} 能够识别的路由条目。
     * 当请求匹配时，通过反射执行对应方法并自动转换返回值。</p>
     *
     * <p>方法参数支持以下注入：
     * <ul>
     *   <li>{@link ServerRequest} — 请求对象</li>
     *   <li>{@link ServerResponse} — 响应对象</li>
     *   <li>{@code String} / {@code int} / {@code long} — 自动从路径变量或查询参数解析</li>
     *   <li>其他类型 — 尝试从 ObjectContext 注入</li>
     * </ul>
     *
     * @param handler 要注册的对象
     * @return 当前服务器实例，支持链式调用
     */
    default Server registerMapper(Object handler) {
        if (handler == null) {
            return this;
        }
        registerBean(handler);

        Class<?> clazz = handler.getClass();
        RequestMethod classMapping = clazz.getAnnotation(RequestMethod.class);
        String[] basePaths = classMapping != null && classMapping.value().length > 0
                ? classMapping.value() : new String[]{""};

        for (Method method : clazz.getMethods()) {
            if (method.isBridge() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            RequestMethod methodMapping = method.getAnnotation(RequestMethod.class);
            if (methodMapping == null) {
                continue;
            }
            HttpMethod[] httpMethods = methodMapping.method();
            for (String basePath : basePaths) {
                for (String subPath : methodMapping.value()) {
                    String path = basePath + subPath;
                    if (httpMethods.length == 0) {
                        registerMapping(path, createReflectiveHandler(clazz, method));
                    } else {
                        for (HttpMethod hm : httpMethods) {
                            registerMapping(path, hm, createReflectiveHandler(clazz, method));
                        }
                    }
                }
            }
        }
        return this;
    }

    /** 创建ReflectiveHandler */
    private ServerHandler createReflectiveHandler(Class<?> targetClass, Method method) {
        return (request, response) -> {
            try {
                // 请求时从 ObjectContext 动态获取 Bean，避免闭包捕获原始实例
                ObjectContext context = getObjectContext();
                Object bean = context != null ? context.getBeanOfType(targetClass) : null;
                if (bean == null) {
                    response.sendError(500, "未找到 Bean 实例: " + targetClass.getName());
                    return;
                }
                Object result = invokeMethod(bean, method, request, response);
                if (result != null) {
                    response.setResult(result);
                }
            } catch (Exception e) {
                if (!response.isEnded()) {
                    response.sendError(500, "Internal Server Error");
                }
            }
        };
    }

    /** 调用Method */
    private Object invokeMethod(Object target, Method method, ServerRequest request, ServerResponse response) throws Exception {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            if (ServerRequest.class.isAssignableFrom(type)) {
                args[i] = request;
            } else if (ServerResponse.class.isAssignableFrom(type)) {
                args[i] = response;
            } else if (type == String.class) {
                args[i] = resolveStringParam(request, params[i]);
            } else if (type == int.class || type == Integer.class) {
                String val = resolveStringParam(request, params[i]);
                args[i] = val != null ? Integer.parseInt(val) : 0;
            } else if (type == long.class || type == Long.class) {
                String val = resolveStringParam(request, params[i]);
                args[i] = val != null ? Long.parseLong(val) : 0L;
            } else if (type == boolean.class || type == Boolean.class) {
                String val = resolveStringParam(request, params[i]);
                args[i] = val != null ? Boolean.parseBoolean(val) : false;
            } else {
                throw new IllegalArgumentException("不支持的请求参数类型: " + type.getName());
            }
        }

        method.setAccessible(true);
        return ReflectUtils.invoke(target, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
    }

    /** 解析StringParam */
    private static String resolveStringParam(ServerRequest request, Parameter param) {
        String name = param.isNamePresent() ? param.getName() : param.getType().getSimpleName().toLowerCase();

        Map<String, String> pathVars = ServerAttribute.getPathVariables(request);
        String value = pathVars.get(name);
        if (value != null) {
            return value;
        }
        value = request.getParam(name);
        if (value != null) {
            return value;
        }
        return null;
    }

    /**
     * 获取 SPI 发现的所有 {@link ResponseConverter}。
     *
     * <p>用于将 handler 返回对象转换为不同格式（JSON/XML/HTML）。</p>
     *
     * @return 名称到 ResponseConverter 的映射
     */
    default Map<String, ResponseConverter> getResponseConverters() {
        return ServiceProvider.of(ResponseConverter.class).list();
    }
}
