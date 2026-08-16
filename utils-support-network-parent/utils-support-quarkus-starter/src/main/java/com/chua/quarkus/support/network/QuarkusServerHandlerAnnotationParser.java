package com.chua.quarkus.support.network;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.http.HttpReflectiveDefaultServerHandler;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 Quarkus / JAX-RS 注解的 {@link com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser} 实现。
 *
 * <p>从 {@link ObjectContext} 中查找标注了 {@link Path} 的 Bean，
 * 解析方法级的 {@link GET}、{@link POST}、{@link PUT}、{@link DELETE}、{@link PATCH}
 * 注解并生成 HTTP 路由处理器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("quarkus")
@ConditionalOnClass("jakarta.ws.rs.Path")
@Slf4j
public class QuarkusServerHandlerAnnotationParser
        implements com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser {

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public List<ServerHandler> parse(ObjectContext objectContext, ServerFilter serverFilter) {
        if(!(serverFilter instanceof UrlMappingServerFilter)) {
            return List.of();
        }

        List<ServerHandler> result = new ArrayList<>();

        Map<String, Object> beans = objectContext.getBeansWithAnnotation(Path.class);
        if (beans == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> clazz = bean.getClass();
            Path classPath = clazz.getAnnotation(Path.class);
            String basePath = classPath != null ? classPath.value() : "";

            for (Method method : clazz.getMethods()) {
                if (method.isBridge() || method.getDeclaringClass() == Object.class) {
                    continue;
                }
                Path methodPath = method.getAnnotation(Path.class);
                String path = joinPath(basePath, methodPath != null ? methodPath.value() : "");
                Set<HttpMethod> httpMethods = resolveHttpMethods(method);
                if (httpMethods == null) {
                    continue;
                }
                if (httpMethods.isEmpty()) {
                    result.add(new HttpReflectiveDefaultServerHandler(bean, method, path, null));
                } else {
                    for (HttpMethod hm : httpMethods) {
                        result.add(new HttpReflectiveDefaultServerHandler(bean, method, path, hm));
                    }
                }
            }
        }
        return result;
    }

    /**
     * 解析方法对应的 HTTP 方法集合。
     *
     * <p>JAX-RS 方法必须标注 {@link GET}、{@link POST}、{@link PUT}、{@link DELETE}、{@link PATCH} 之一，
     * 未标注任何 HTTP 方法注解时返回 null（非法资源方法，跳过）。</p>
     *
     * @param method 目标方法
     * @return HTTP 方法集合，空集合表示匹配所有方法，null 表示跳过
     */
    private Set<HttpMethod> resolveHttpMethods(Method method) {
        Set<HttpMethod> result = new HashSet<>();
        if (method.isAnnotationPresent(GET.class)) {
            result.add(HttpMethod.GET);
        }
        if (method.isAnnotationPresent(POST.class)) {
            result.add(HttpMethod.POST);
        }
        if (method.isAnnotationPresent(PUT.class)) {
            result.add(HttpMethod.PUT);
        }
        if (method.isAnnotationPresent(DELETE.class)) {
            result.add(HttpMethod.DELETE);
        }
        if (method.isAnnotationPresent(PATCH.class)) {
            result.add(HttpMethod.PATCH);
        }
        if (result.isEmpty()) {
            return null;
        }
        return result;
    }

    /**
     * 拼接类级路径和方法级路径。
     *
     * @param prefix 类级路径
     * @param suffix 方法级路径
     * @return 拼接后的完整路径
     */
    private static String joinPath(String prefix, String suffix) {
        if (prefix.isEmpty()) {
            return suffix.startsWith("/") ? suffix : "/" + suffix;
        }
        if (suffix.isEmpty()) {
            return prefix.startsWith("/") ? prefix : "/" + prefix;
        }
        String left = prefix.startsWith("/") ? prefix : "/" + prefix;
        if (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        String right = suffix.startsWith("/") ? suffix.substring(1) : suffix;
        return left + "/" + right;
    }

}