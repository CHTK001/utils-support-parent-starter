package com.chua.common.support.network.server.parser;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.annotations.RequestMethod;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.http.HttpReflectiveDefaultServerHandler;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.definition.MethodDefinition;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@link RequestMethod} 注解的 {@link ServerHandlerAnnotationParser} 实现。
 *
 * <p>从 {@link ObjectContext} 中查找类或方法上标注了 {@link RequestMethod} 的 Bean，
 * 生成 HTTP 路由处理器。类级注解作为公共前缀，方法级注解定义具体路径和 HTTP 方法。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi("request-method")
@Slf4j
public class RequestMethodServerHandlerParser implements ServerHandlerAnnotationParser {

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 0;
    }

    @Override
    /**
     * 解析
    */
    public List<ServerHandler> parse(ObjectContext objectContext, ServerFilter serverFilter) {
        if(!(serverFilter instanceof UrlMappingServerFilter)) {
            return List.of();
        }

        List<ServerHandler> result = new ArrayList<>();

        // 处理类级 @RequestMethod 注解的 Bean
        Map<String, Object> classBeans = objectContext.getBeansWithAnnotation(RequestMethod.class);
        if (classBeans != null) {
            for (Map.Entry<String, Object> entry : classBeans.entrySet()) {
                Object bean = entry.getValue();
                Class<?> clazz = bean.getClass();
                RequestMethod classMapping = clazz.getAnnotation(RequestMethod.class);
                String[] basePaths = classMapping != null && classMapping.value().length > 0
                        ? classMapping.value() : new String[]{""};
                for (String basePath : basePaths) {
                    for (Method method : clazz.getMethods()) {
                        if (method.isBridge() || method.getDeclaringClass() == Object.class) {
                            continue;
                        }
                        RequestMethod ann = method.getAnnotation(RequestMethod.class);
                        if (ann == null) {
                            continue;
                        }
                        addReflectiveHandlers(result, bean, method, prependBase(basePath, ann.value()), ann.method());
                    }
                }
            }
        }

        // 处理方法级 @RequestMethod 注解（类上无注解时）
        List<MethodDefinition> methodDefs = objectContext.getMethodWithAnnotation(RequestMethod.class);
        if (methodDefs != null) {
            for (MethodDefinition md : methodDefs) {
                RequestMethod ann = md.getMethod().getAnnotation(RequestMethod.class);
                if (ann == null) {
                    continue;
                }
                addReflectiveHandlers(result, md.getBean(), md.getMethod(), ann.value(), ann.method());
            }
        }

        return result;
    }

    /**
     * 前置Base
     * @param basePath base路径，不允许为 null
     * @param subPaths 方法入参 subPaths
     * @return 字符串 对象
     */
    private static String[] prependBase(String basePath, String[] subPaths) {
        String[] result = new String[subPaths.length];
        for (int i = 0; i < subPaths.length; i++) {
            String p = basePath + subPaths[i];
            result[i] = p.isEmpty() ? "/" : p;
        }
        return result;
    }

    /**
     * 添加ReflectiveHandlers
     * @param result result
     * @param bean bean
     * @param method method
     * @param paths paths
     * @param httpMethods httpMethods
     */
    private static void addReflectiveHandlers(List<ServerHandler> result, Object bean, Method method,
                                              String[] paths, HttpMethod[] httpMethods) {
        for (String path : paths) {
            String p = path.isEmpty() ? "/" : path;
            if (httpMethods.length == 0) {
                result.add(new HttpReflectiveDefaultServerHandler(bean, method, p, null));
            } else {
                for (HttpMethod hm : httpMethods) {
                    result.add(new HttpReflectiveDefaultServerHandler(bean, method, p, hm));
                }
            }
        }
    }

}
