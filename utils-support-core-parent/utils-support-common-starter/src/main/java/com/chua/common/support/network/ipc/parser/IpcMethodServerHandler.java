package com.chua.common.support.network.ipc.parser;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.http.HttpDefaultServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IPC 方法反射处理器。
 *
 * <p>将 IPC 请求通过反射转发到目标 Bean 的方法上，自动从 JSON 请求体解析参数并返回 JSON 响应。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
public class IpcMethodServerHandler implements HttpDefaultServerHandler {

    /** Object上下文 */
    private final ObjectContext objectContext;
    private final Class<?> targetClass;
    /** Method */
    private final Method method;
    /** 路径 */
    private final String path;

    /**
     * 创建 IpcMethodServerHandler 实例
     * @param objectContext objectContext
     * @param Class Class
     * @param targetClass targetClass
     * @param Method Method
     * @param String String
     * @param method 方法，不允许为 null
     * @param path 路径，不允许为 null
     */
    public IpcMethodServerHandler(ObjectContext objectContext, Class<?> targetClass, Method method, String path) {
        this.objectContext = objectContext;
        this.targetClass = targetClass;
        this.method = method;
        this.path = path;
    }

    @Override
    /** Path */
    public String path() {
        return path;
    }

    @Override
    /** Method */
    public HttpMethod method() {
        return null;
    }

    @Override
    /** 处理 */
    public void handle(ServerRequest request, ServerResponse response) throws Exception {
        Object bean = objectContext.getBeanOfType(targetClass);
        if (bean == null) {
            sendError(response, 500, "未找到 Bean 实例: " + targetClass.getName());
            return;
        }

        String body = request.getBodyString();
        Map<String, Object> map = body != null ? Json.fromJson(body, Map.class) : new LinkedHashMap<>();
        if (map == null) {
            map = new LinkedHashMap<>();
        }

        Object[] args = resolveArgs(map.get("params"), method.getParameters());

        try {
            Object result = ReflectUtils.invoke(bean, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("success", true);
            resultMap.put("result", result);
            response.setStatus(200);
            response.setContentType("application/json; charset=utf-8");
            response.setBody(Json.toJson(resultMap));
            response.end();
        } catch (Exception e) {
            sendError(response, 500, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    /**
     * 解析Args
     * @param params 参数，不允许为 null
     * @param parameters 方法入参 parameters
     * @return 对象 对象
     */
    private static Object[] resolveArgs(Object params, Parameter[] parameters) {
        if (params == null) {
            return new Object[parameters.length];
        }
        Object[] pa = params instanceof java.util.List<?> list ? list.toArray() : new Object[]{params};
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            args[i] = i < pa.length ? convertParam(pa[i], parameters[i].getType()) : null;
        }
        return args;
    }

    /**
     * 转换Param
     * @param v 方法入参 v
     * @param t 方法入参 t
     * @return 对象 对象
     */
    private static Object convertParam(Object v, Class<?> t) {
        if (v == null) {
            return null;
        }
        if (t.isInstance(v)) {
            return v;
        }
        String s = v.toString();
        if (t == String.class) {
            return s;
        }
        if (t == int.class || t == Integer.class) {
            return Integer.parseInt(s);
        }
        if (t == long.class || t == Long.class) {
            return Long.parseLong(s);
        }
        if (t == double.class || t == Double.class) {
            return Double.parseDouble(s);
        }
        if (t == boolean.class || t == Boolean.class) {
            return Boolean.parseBoolean(s);
        }
        return v;
    }

    /**
     * 发送记录错误
     * @param response 响应，不允许为 null
     * @param status 状态，不允许为 null
     * @param msg 消息，不允许为 null
     */
    private static void sendError(ServerResponse response, int status, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", msg);
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.setBody(Json.toJson(body));
        response.end();
    }
}
