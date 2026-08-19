package com.chua.common.support.network.server;

import com.chua.common.support.network.server.request.ServerRequest;

import java.util.Map;

/**
 * 请求级变量容器，用于在一次请求的生命周期内在多个 Filter 之间传递数据。
 *
 * <p>典型使用场景：HTTP 反向代理 filter 从 ServiceDiscovery 获取后端地址后，
 * 通过 {@link #setAttribute(ServerRequest, String, Object)} 存入，
 * 下游的代理 filter 通过 {@link #getAttribute(ServerRequest, String)} 读取。</p>
 *
 * <p>预定义常量作为常用属性键：</p>
 * <ul>
 *   <li>{@link #BACKEND_ADDRESS} — 反向代理后端地址</li>
 *   <li>{@link #ROUTE_PATTERN} — 匹配的路由模式</li>
 *   <li>{@link #PATH_VARIABLES} — 路径模板变量 Map</li>
 * </ul>
 *
 * @since 2026/07/16
 */
public final class ServerAttribute {

    /** 反向代理后端目标地址 (host:port) */
    public static final String BACKEND_ADDRESS = "__backend_address";

    /** 反向代理后端协议 (http/https) */
    public static final String BACKEND_SCHEME = "__backend_scheme";

    /** 反向代理后端主机 */
    public static final String BACKEND_HOST = "__backend_host";

    /** 反向代理后端端口 */
    public static final String BACKEND_PORT = "__backend_port";

    /** 反向代理后端完整 URI */
    public static final String BACKEND_URI = "__backend_uri";

    /** 匹配的 Ant 路由模式 */
    public static final String ROUTE_PATTERN = "__route_pattern";

    /** 路径模板变量集 ({@code {var} -> value}) */
    public static final String PATH_VARIABLES = "__path_variables";

    /** 已认证用户标识 */
    public static final String AUTH_PRINCIPAL = "__auth_principal";

    /** 原始请求路径（重写前） */
    public static final String ORIGINAL_PATH = "__original_path";

    /** 服务发现对象（ServiceDiscoveryServerFilter 存入） */
    public static final String BACKEND_DISCOVERY = "__backend_discovery";

    /** 底层 Vert.x RoutingContext（VertxHttpServer 存入，供 WebSocket 代理升级使用） */
    public static final String VERTX_ROUTING_CONTEXT = "__vertx_routing_context";

    /** 创建 ServerAttribute 实例 */
    private ServerAttribute() {
    }

    /**
     * 获取请求属性。
     *
     * @param request 请求对象
     * @param name    属性名
     * @param <T>     属性类型
     * @return 属性值，不存在返回 null
     */
@SuppressWarnings("unchecked")
    public static <T> T getAttribute(ServerRequest request, String name) {
        return (T) request.getAttribute(name);
    }

    /**
     * 设置请求属性。
     *
     * @param request 请求对象
     * @param name    属性名
     * @param value   属性值
     */
    public static void setAttribute(ServerRequest request, String name, Object value) {
        request.setAttribute(name, value);
    }

    /**
     * 获取所有请求属性。
     *
     * @param request 请求对象
     * @return 属性 Map
     */
    public static Map<String, Object> getAttributes(ServerRequest request) {
        return request.getAttributes();
    }

    /**
     * 获取反向代理后端地址。
     *
     * @param request 请求对象
     * @return 后端地址，不存在返回 null
     */
    public static String getBackendAddress(ServerRequest request) {
        return getAttribute(request, BACKEND_ADDRESS);
    }

    /**
     * 设置反向代理后端地址。
     *
     * @param request          请求对象
     * @param backendAddress   后端地址
     */
    public static void setBackendAddress(ServerRequest request, String backendAddress) {
        setAttribute(request, BACKEND_ADDRESS, backendAddress);
    }

    /**
     * 获取反向代理后端协议。
     *
     * @param request 请求对象
     * @return 协议 (http/https)，不存在返回 null
     */
    public static String getBackendScheme(ServerRequest request) {
        return getAttribute(request, BACKEND_SCHEME);
    }

    /**
     * 设置反向代理后端协议。
     *
     * @param request 请求对象
     * @param scheme  协议 (http/https)
     */
    public static void setBackendScheme(ServerRequest request, String scheme) {
        setAttribute(request, BACKEND_SCHEME, scheme);
    }

    /**
     * 获取反向代理后端主机。
     *
     * @param request 请求对象
     * @return 主机名
     */
    public static String getBackendHost(ServerRequest request) {
        return getAttribute(request, BACKEND_HOST);
    }

    /**
     * 设置反向代理后端主机。
     *
     * @param request 请求对象
     * @param host    主机名
     */
    public static void setBackendHost(ServerRequest request, String host) {
        setAttribute(request, BACKEND_HOST, host);
    }

    /**
     * 获取反向代理后端端口。
     *
     * @param request 请求对象
     * @return 端口号
     */
    public static Integer getBackendPort(ServerRequest request) {
        return getAttribute(request, BACKEND_PORT);
    }

    /**
     * 设置反向代理后端端口。
     *
     * @param request 请求对象
     * @param port    端口号
     */
    public static void setBackendPort(ServerRequest request, Integer port) {
        setAttribute(request, BACKEND_PORT, port);
    }

    /**
     * 获取反向代理后端完整 URI。
     *
     * @param request 请求对象
     * @return 完整 URI
     */
    public static String getBackendUri(ServerRequest request) {
        return getAttribute(request, BACKEND_URI);
    }

    /**
     * 设置反向代理后端完整 URI。
     *
     * @param request 请求对象
     * @param uri     完整 URI
     */
    public static void setBackendUri(ServerRequest request, String uri) {
        setAttribute(request, BACKEND_URI, uri);
    }

    /**
     * 获取匹配的路由模式。
     *
     * @param request 请求对象
     * @return 路由模式，不存在返回 null
     */
    public static String getRoutePattern(ServerRequest request) {
        return getAttribute(request, ROUTE_PATTERN);
    }

    /**
     * 设置匹配的路由模式。
     *
     * @param request  请求对象
     * @param pattern  路由模式
     */
    public static void setRoutePattern(ServerRequest request, String pattern) {
        setAttribute(request, ROUTE_PATTERN, pattern);
    }

    /**
     * 获取路径模板变量。
     *
     * @param request 请求对象
     * @return 变量 Map，不存在返回空 Map
     */
    public static Map<String, String> getPathVariables(ServerRequest request) {
        Map<String, String> vars = (Map<String, String>) request.getAttribute(PATH_VARIABLES);
        return vars != null ? vars : Map.of();
    }

    /**
     * 设置路径模板变量。
     *
     * @param request     请求对象
     * @param variables   变量映射
     */
    public static void setPathVariables(ServerRequest request, Map<String, String> variables) {
        setAttribute(request, PATH_VARIABLES, variables);
    }

    /**
     * 获取认证用户标识。
     *
     * @param request 请求对象
     * @return 用户标识
     */
    public static Object getAuthPrincipal(ServerRequest request) {
        return getAttribute(request, AUTH_PRINCIPAL);
    }

    /**
     * 设置认证用户标识。
     *
     * @param request   请求对象
     * @param principal 用户标识
     */
    public static void setAuthPrincipal(ServerRequest request, Object principal) {
        setAttribute(request, AUTH_PRINCIPAL, principal);
    }

    /**
     * 获取原始请求路径（重写前）。
     *
     * @param request 请求对象
     * @return 原始路径
     */
    public static String getOriginalPath(ServerRequest request) {
        return getAttribute(request, ORIGINAL_PATH);
    }

    /**
     * 设置原始请求路径（重写前）。
     *
     * @param request 请求对象
     * @param path    原始路径
     */
    public static void setOriginalPath(ServerRequest request, String path) {
        setAttribute(request, ORIGINAL_PATH, path);
    }

    /**
     * 获取服务发现对象。
     *
     * @param request 请求对象
     * @return Discovery 对象，不存在返回 null
     */
    public static com.chua.common.support.network.discovery.Discovery getBackendDiscovery(ServerRequest request) {
        return getAttribute(request, BACKEND_DISCOVERY);
    }

    /**
     * 设置服务发现对象。
     *
     * @param request   请求对象
     * @param discovery Discovery 对象
     */
    public static void setBackendDiscovery(ServerRequest request,
                                           com.chua.common.support.network.discovery.Discovery discovery) {
        setAttribute(request, BACKEND_DISCOVERY, discovery);
    }
}
