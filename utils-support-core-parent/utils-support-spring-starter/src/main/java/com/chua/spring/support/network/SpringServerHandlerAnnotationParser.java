package com.chua.spring.support.network;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.http.HttpReflectiveDefaultServerHandler;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 Spring MVC 注解的 {@link com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser} 实现。
 *
 * <p>从 {@link ObjectContext} 中查找 {@link Controller} 或 {@link RestController} Bean，
 * 解析方法级的 {@link RequestMapping}、{@link GetMapping}、{@link PostMapping}、
 * {@link PutMapping}、{@link DeleteMapping}、{@link PatchMapping} 注解并生成路由。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi("spring")
@ConditionalOnClass("org.springframework.web.bind.annotation.RequestMapping")
@Slf4j
public class SpringServerHandlerAnnotationParser
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

        Map<String, Object> all = new LinkedHashMap<>();
        Map<String, Object> controllerBeans = objectContext.getBeansWithAnnotation(Controller.class);
        Map<String, Object> restControllerBeans = objectContext.getBeansWithAnnotation(RestController.class);
        if (controllerBeans != null) {
            all.putAll(controllerBeans);
        }
        if (restControllerBeans != null) {
            all.putAll(restControllerBeans);
        }
        for (Object bean : all.values()) {
            parseController(bean, result);
        }
        return result;
    }

    /**
     * 解析控制器类中所有方法级映射注解。
     *
     * @param bean   Bean 实例
     * @param result 处理器列表
     */
    private void parseController(Object bean, List<ServerHandler> result) {
        Class<?> clazz = bean.getClass();
        String classPath = resolveClassPath(clazz);

        for (Method method : clazz.getMethods()) {
            if (method.isBridge() || method.getDeclaringClass() == Object.class) {
                continue;
            }
            List<String> methodPaths = resolveMethodPaths(method);
            if (methodPaths.isEmpty()) {
                continue;
            }
            Set<HttpMethod> httpMethods = resolveHttpMethods(method);
            for (String methodPath : methodPaths) {
                String path = joinPath(classPath, methodPath);
                if (httpMethods.isEmpty()) {
                    result.add(new HttpReflectiveDefaultServerHandler(bean, method, path, null));
                } else {
                    for (HttpMethod hm : httpMethods) {
                        result.add(new HttpReflectiveDefaultServerHandler(bean, method, path, hm));
                    }
                }
            }
        }
    }

    /**
     * 解析类级 {@link RequestMapping} 路径前缀。
     *
     * @param clazz 控制器类型
     * @return 路径前缀
     */
    private String resolveClassPath(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        if (rm != null && rm.value().length > 0) {
            return rm.value()[0];
        }
        return "";
    }

    /**
     * 解析方法级映射注解的路径列表。
     *
     * <p>优先判断具体注解（{@link GetMapping}、{@link PostMapping} 等），
     * 最后回退到通用 {@link RequestMapping}。支持一个方法映射多个路径。</p>
     *
     * @param method 目标方法
     * @return 路径列表，无映射注解返回空列表
     */
    private List<String> resolveMethodPaths(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            GetMapping gm = method.getAnnotation(GetMapping.class);
            if (gm.value().length > 0) {
                return List.of(gm.value());
            }
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            PostMapping pm = method.getAnnotation(PostMapping.class);
            if (pm.value().length > 0) {
                return List.of(pm.value());
            }
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            PutMapping pm = method.getAnnotation(PutMapping.class);
            if (pm.value().length > 0) {
                return List.of(pm.value());
            }
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            DeleteMapping dm = method.getAnnotation(DeleteMapping.class);
            if (dm.value().length > 0) {
                return List.of(dm.value());
            }
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            PatchMapping pm = method.getAnnotation(PatchMapping.class);
            if (pm.value().length > 0) {
                return List.of(pm.value());
            }
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping rm = method.getAnnotation(RequestMapping.class);
            if (rm.value().length > 0) {
                return List.of(rm.value());
            }
        }
        return List.of();
    }

    /**
     * 解析方法对应的 HTTP 方法集合。
     *
     * <p>具体注解（{@link GetMapping} 等）返回单元素集合；
     * {@link RequestMapping} 按 {@code method} 属性返回对应集合，未指定时返回空集合（匹配所有方法）。</p>
     *
     * @param method 目标方法
     * @return HTTP 方法集合，空集合表示匹配所有方法
     */
    private Set<HttpMethod> resolveHttpMethods(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return Set.of(HttpMethod.GET);
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return Set.of(HttpMethod.POST);
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return Set.of(HttpMethod.PUT);
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return Set.of(HttpMethod.DELETE);
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return Set.of(HttpMethod.PATCH);
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping rm = method.getAnnotation(RequestMapping.class);
            if (rm.method().length > 0) {
                Set<HttpMethod> result = new HashSet<>();
                for (var m : rm.method()) {
                    try {
                        result.add(HttpMethod.valueOf(m.name()));
                    } catch (IllegalArgumentException e) {
                        log.warn("[spring-network] 忽略非法 HTTP 方法 [{}]", m.name());
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return Set.of();
    }

    /**
     * 拼接类级路径前缀和方法级路径。
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
            return prefix;
        }
        String left = prefix.endsWith("/") ? prefix : prefix + "/";
        String right = suffix.startsWith("/") ? suffix.substring(1) : suffix;
        return left + right;
    }

}