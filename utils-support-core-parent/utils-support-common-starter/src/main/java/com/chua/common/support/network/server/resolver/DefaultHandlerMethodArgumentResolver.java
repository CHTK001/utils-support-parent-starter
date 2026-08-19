package com.chua.common.support.network.server.resolver;

import com.chua.common.support.network.server.ServerAttribute;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Method;
import java.util.Map;


/**
 * 默认处理器方法参数解析器。
 *
 * <p>支持以下类型：
 * <ul>
 *   <li>{@link ServerRequest} — 直接传入</li>
 *   <li>{@link ServerResponse} — 直接传入</li>
 *   <li>{@link String} — 从路径变量、查询参数按参数名解析</li>
 *   <li>{@code int} / {@link Integer} — 解析字符串后转换</li>
 *   <li>{@code long} / {@link Long} — 解析字符串后转换</li>
 *   <li>{@code boolean} / {@link Boolean} — 解析字符串后转换</li>
 * </ul>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi("default")
public class DefaultHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    /** 是否Support */
    public boolean isSupport(Method method, int parameterIndex, ServerRequest request) {
        Class<?> type = method.getParameterTypes()[parameterIndex];
        return type == ServerRequest.class ||
                type == ServerResponse.class ||
                type == String.class ||
                type == int.class || type == Integer.class ||
                type == long.class || type == Long.class ||
                type == boolean.class || type == Boolean.class;
    }

    @Override
    /** 解析 */
    public Object resolve(Method method, int parameterIndex, ServerRequest request, ServerResponse response) {
        Class<?> type = method.getParameterTypes()[parameterIndex];
        if (type == ServerRequest.class) {
            return request;
        }
        if (type == ServerResponse.class) {
            return response;
        }
        String raw = resolveStringParam(request, method, parameterIndex);
        if (type == String.class) {
            return raw;
        }
        if (type == int.class || type == Integer.class) {
            return raw != null ? Integer.parseInt(raw) : 0;
        }
        if (type == long.class || type == Long.class) {
            return raw != null ? Long.parseLong(raw) : 0L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return raw != null ? Boolean.parseBoolean(raw) : false;
        }
        return null;
    }

    /** 解析StringParam */
    private static String resolveStringParam(ServerRequest request, Method method, int index) {
        java.lang.reflect.Parameter param = method.getParameters()[index];
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
}
