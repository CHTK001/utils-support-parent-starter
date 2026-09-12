package com.chua.common.support.network.ipc.parser;

import com.chua.common.support.network.ipc.annotations.IpcMethod;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.filter.IpcServerFilter;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.definition.MethodDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.UrlUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
* 解析 {@link IpcMethod} 注解的 {@link ServerHandlerAnnotationParser} 实现。
* <p>
* 从 {@link ObjectContext} 中查找类或方法上标注了 {@link IpcMethod} 的 Bean，
* 生成 IPC 路由处理器。类级注解作为公共前缀，方法级注解定义具体路径。
* </p>
*
* @author CH
* @since 2026/07/18
 */
@Spi("ipc-method")
public class IpcMethodServerHandlerParser implements ServerHandlerAnnotationParser {

    /**
    * 获取解析器的优先级。
    *
    * @return 优先级值
     */
    @Override
    public int getPriority() {
        return 0;
    }

    /**
    * 解析并生成 IPC 路由处理器列表。
    *
    * @param objectContext   对象上下文，包含所有 Bean 和方法定义信息
    * @param serverFilter    服务器过滤器，用于过滤特定类型的处理请求
    * @return 生成的 {@link ServerHandler} 列表
     */
    @Override
    public List<ServerHandler> parse(ObjectContext objectContext, ServerFilter serverFilter) {
        if (!(serverFilter instanceof IpcServerFilter)) {
            return List.of();
        }
        List<ServerHandler> result = new ArrayList<>();
        if (objectContext == null) {
            return result;
        }

        Set<String> processedClasses = new HashSet<>();

        // 处理类级 @IpcMethod 注解的 Bean
        Map<String, Object> classBeans = objectContext.getBeansWithAnnotation(IpcMethod.class);
        if (classBeans != null) {
            for (Map.Entry<String, Object> entry : classBeans.entrySet()) {
                Object bean = entry.getValue();
                Class<?> clazz = bean.getClass();
                processedClasses.add(clazz.getName());
                String basePath = extractClassPath(clazz);
                for (Method method : clazz.getDeclaredMethods()) {
                    IpcMethod ann = method.getAnnotation(IpcMethod.class);
                    if (ann == null) {
                        continue;
                    }
                    String fullPath = joinPath(basePath, ann.value().trim());
                    method.setAccessible(true);
                    result.add(new IpcMethodServerHandler(objectContext, clazz, method, UrlUtils.normalizePath(fullPath)));
                }
            }
        }

        // 处理方法级 @IpcMethod 注解（类上无注解时）
        List<MethodDefinition> methodDefs = objectContext.getMethodWithAnnotation(IpcMethod.class);
        if (methodDefs != null) {
            for (MethodDefinition md : methodDefs) {
                IpcMethod ann = md.getMethod().getAnnotation(IpcMethod.class);
                if (ann == null) {
                    continue;
                }
                Class<?> clazz = md.getBean().getClass();
                if (!processedClasses.add(clazz.getName())) {
                    continue;
                }
                String path = UrlUtils.normalizePath(ann.value().trim());
                result.add(new IpcMethodServerHandler(objectContext, clazz, md.getMethod(), path));
            }
        }

        return result;
    }

    /**
    * 提取类上的路径前缀。
    *
    * @param clazz 需要提取路径的类
    * @return 类上的路径值，若无则返回空字符串
     */
    private static String extractClassPath(Class<?> clazz) {
        IpcMethod ann = clazz.getAnnotation(IpcMethod.class);
        if (ann != null) {
            return ann.value().trim();
        }
        return "";
    }

    /**
    * 拼接基础路径和方法路径。
    *
    * @param basePath      基础路径
    * @param methodName    方法路径
    * @return 拼接后的完整路径
     */
    private static String joinPath(String basePath, String methodName) {
        StringBuilder sb = new StringBuilder();
        if (basePath != null && !basePath.isEmpty()) {
            if (!basePath.startsWith("/")) {
                sb.append("/");
            }
            sb.append(basePath);
        }
        if (methodName != null && !methodName.isEmpty()) {
            if (!methodName.startsWith("/") && !sb.isEmpty() && !sb.toString().endsWith("/")) {
                sb.append("/");
            }
            sb.append(methodName);
        }
        String result = sb.toString();
        if (result.isEmpty()) {
            return "/";
        }
        return result;
    }

}
