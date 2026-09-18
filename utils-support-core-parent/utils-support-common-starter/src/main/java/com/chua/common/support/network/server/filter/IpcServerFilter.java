package com.chua.common.support.network.server.filter;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.handler.ServerHandlerFactory;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
* IPC 服务器过滤器，拦截 /ipc/* 请求路由到处理器。
*
* <p>使用 {@link ServerHandlerFactory} 统一管理 IPC 方法路由，
* 通过 {@link com.chua.common.support.network.ipc.parser.IpcMethodServerHandlerParser} 扫描 {@link com.chua.common.support.network.ipc.annotations.IpcMethod} 注解。</p>
*
* @author CH
* @since 2026/07/18
 */
@Slf4j
public class IpcServerFilter implements ServerFilter {

    /**
    * 处理器工厂，管理 IPC 方法路由
    */
    private final ServerHandlerFactory<ServerHandlerAnnotationParser> factory;

    /**
    * 构造 IPC 服务器过滤器。
    *
    * @param objectContext 对象上下文
    */
    public IpcServerFilter(ObjectContext objectContext) {
        this.factory = new ServerHandlerFactory<>(objectContext);
        this.factory.initialize(ServerHandlerAnnotationParser.class, this);
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MAX_VALUE - 200;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP, ProtocolType.IPC};
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null || !path.startsWith("/ipc")) {
            chain.doFilter(request, response);
            return;
        }
        handleIpcRequest(request, response);
    }

    /**
     * 处理IpcRequest
     * @param request 请求，不允许为 null
     * @param response 响应，不允许为 null
     */
    private void handleIpcRequest(ServerRequest request, ServerResponse response) {
        try {
            String body = request.getBodyString();
            if (body == null || body.isEmpty()) {
                sendError(response, 400, "请求体为空");
                return;
            }
            Map<String, Object> map;
            try {
                map = Json.fromJson(body, Map.class);
            } catch (Exception e) {
                sendError(response, 400, "JSON 格式错误: " + e.getMessage());
                return;
            }
            if (map == null) {
                sendError(response, 400, "请求体格式错误");
                return;
            }
            String method = (String) map.get("method");
            if (method == null || method.isEmpty()) {
                sendError(response, 400, "缺少 method");
                return;
            }

            ServerHandler handler = factory.resolveHandler(normalizePath(method));
            if (handler == null) {
                sendError(response, 404, "IPC 方法不存在: " + method);
                return;
            }

            handler.handle(request, response);
        } catch (Exception e) {
            log.error("IPC 请求处理异常: {}", e.getMessage(), e);
            sendError(response, 500, "IPC 方法调用失败: " + e.getMessage());
        }
    }

    /**
     * NormalizePath
     * @param methodName 方法名称，不允许为 null
     * @return 结果字符串
     */
    private static String normalizePath(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return "/";
        }
        return methodName.startsWith("/") ? methodName : "/" + methodName;
    }

    /**
     * 发送记录错误
     * @param res 方法入参 res
     * @param status 状态，不允许为 null
     * @param msg 消息，不允许为 null
     */
    private void sendError(ServerResponse res, int status, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", msg);
        res.setStatus(status);
        res.setContentType("application/json; charset=utf-8");
        res.setBody(Json.toJson(body));
        res.end();
    }
}
