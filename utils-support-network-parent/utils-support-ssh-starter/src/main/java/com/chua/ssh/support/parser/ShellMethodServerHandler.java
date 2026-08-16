package com.chua.ssh.support.parser;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.view.ViewParser;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.http.HttpDefaultServerHandler;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.ssh.support.server.SshCommandResponse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Shell 命令反射处理器。
 *
 * <p>将 Shell 命令请求通过反射转发到目标 Bean 的方法上，支持参数解析、流式输出和视图渲染。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShellMethodServerHandler implements HttpDefaultServerHandler {

    private final ObjectContext objectContext;
    private final Class<?> targetClass;
    private final Method method;
    private final String path;
    private final String produce;

    public ShellMethodServerHandler(ObjectContext objectContext, Class<?> targetClass, Method method, String path, String produce) {
        this.objectContext = objectContext;
        this.targetClass = targetClass;
        this.method = method;
        this.path = path;
        this.produce = produce;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public HttpMethod method() {
        return null;
    }

    @Override
    public void handle(ServerRequest request, ServerResponse response) throws Exception {
        Object bean = objectContext.getBeanOfType(targetClass);
        if (bean == null) {
            response.sendError(500, "未找到 Bean 实例: " + targetClass.getName());
            return;
        }

        String[] args = resolveArgs(request);
        Class<?>[] paramTypes = method.getParameterTypes();

        boolean streaming = Arrays.stream(paramTypes)
                .anyMatch(SshCommandResponse.class::isAssignableFrom);

        Object result;
        if (streaming) {
            Object[] invokeArgs = buildArgs(paramTypes, args, (SshCommandResponse) response);
            method.invoke(bean, invokeArgs);
            return;
        }

        if (paramTypes.length == 1 && paramTypes[0] == String[].class) {
            result = method.invoke(bean, new Object[]{args});
        } else if (paramTypes.length == 0) {
            result = method.invoke(bean);
        } else {
            result = method.invoke(bean, new Object[]{args});
        }

        if (result != null) {
            response.setBody(renderView(result, produce));
        }
    }

    private static String[] resolveArgs(ServerRequest request) {
        List<String> args = new java.util.ArrayList<>();
        for (int i = 0; ; i++) {
            String param = request.getParam(String.valueOf(i));
            if (param == null) {
                break;
            }
            args.add(param);
        }
        return args.toArray(new String[0]);
    }

    private static Object[] buildArgs(Class<?>[] paramTypes, String[] args, SshCommandResponse sshRes) {
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == String[].class) {
                result[i] = args;
            } else if (SshCommandResponse.class.isAssignableFrom(paramTypes[i])) {
                result[i] = sshRes;
            } else {
                result[i] = null;
            }
        }
        return result;
    }

    private static String renderView(Object data, String produce) {
        if (data == null) {
            return "";
        }
        if (data instanceof String s) {
            return s;
        }
        Map<String, ViewParser> parsers = ServiceProvider.of(ViewParser.class).list();
        if (produce != null && !produce.isEmpty()) {
            ViewParser parser = parsers.get(produce);
            if (parser != null) {
                return parser.render(data);
            }
        }
        for (ViewParser parser : parsers.values().stream()
                .sorted(Comparator.comparingInt(ViewParser::getOrder)).toList()) {
            if (parser.support(data)) {
                return parser.render(data);
            }
        }
        return data.toString();
    }
}
