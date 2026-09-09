package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.MappingDefinition;
import com.chua.common.support.objects.describe.MethodDescribe;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.ServletHandler;
import com.chua.common.support.network.protocol.server.ServletHandlerMappingDefinition;
import com.chua.common.support.objects.describe.ServletHandlerMethodDescribe;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认映射注册表实现
 * <p>
 * 提供基于内存的映射定义存储和查找功能。
 * 支持精确匹配和路径参数匹配（如/users/{id}）。
 *
 * @author CH
 * @since 2024/7/9
 */
@Slf4j
@Spi("mapping")
@SpiDescribe("映射注册表")
@SpiSupport("http")
public class DefaultMappingRegistry implements MappingRegistry {

    /**
     * 精确路径映射存储
     * Key: HTTP方法 + 路径 (如"GET:/users")
     * Value: 映射定义
     */
    private final Map<String, MappingDefinition> exactMappings = new ConcurrentHashMap<>();

    /**
     * 模式路径映射存储
     * 用于存储包含路径参数的映射（如/users/{id}）
     */
    private final List<PatternMapping> patternMappings = new CopyOnWriteArrayList<>();

    /**
     * 所有映射定义列表
     */
    private final List<MappingDefinition> allMappings = new CopyOnWriteArrayList<>();

    private final ObjectContext objectContext;

    public DefaultMappingRegistry(ObjectContext objectContext) {
        this.objectContext = objectContext;
    }

    @Override
    public void registerMapping(MappingDefinition mappingDefinition) {
        if (mappingDefinition == null) {
            log.warn("映射定义为null，跳过注册");
            return;
        }

        String[] urls = mappingDefinition.url();
        HttpMethod[] methods = mappingDefinition.method();

        if (urls == null || urls.length == 0) {
            log.warn("映射定义的URL为空，跳过注册");
            return;
        }

        if (methods == null || methods.length == 0) {
            log.warn("映射定义的HTTP方法为空，跳过注册");
            return;
        }

        // 为每个URL和HTTP方法组合注册映射
        for (String url : urls) {
            for (HttpMethod method : methods) {
                registerSingleMapping(url, method.name(), mappingDefinition);
            }
        }

        // 添加到总列表
        allMappings.add(mappingDefinition);

        MethodDescribe methodDescribe = mappingDefinition.getMethodDescribe();
        String methodName = methodDescribe != null ? methodDescribe.getName() : "ServletHandler";
        if (log.isDebugEnabled()) {
            log.debug("注册映射定义: {} -> {}", Arrays.toString(urls), methodName);
        }
    }

    @Override
    public void registerMappings(List<MappingDefinition> mappingDefinitions) {
        if (mappingDefinitions == null || mappingDefinitions.isEmpty()) {
            return;
        }

        for (MappingDefinition mapping : mappingDefinitions) {
            registerMapping(mapping);
        }
    }

    @Override
    public void unregisterMapping(MappingDefinition mappingDefinition) {
        if (mappingDefinition == null) {
            return;
        }

        String[] urls = mappingDefinition.url();
        HttpMethod[] methods = mappingDefinition.method();

        if (urls != null && methods != null) {
            for (String url : urls) {
                for (HttpMethod method : methods) {
                    String key = method.name() + ":" + url;
                    exactMappings.remove(key);
                }
            }
        }

        // 从模式映射中移除
        patternMappings.removeIf(pm -> pm.mappingDefinition.equals(mappingDefinition));

        // 从总列表中移除
        allMappings.remove(mappingDefinition);

        if (log.isDebugEnabled()) {
            log.debug("取消注册映射定义: {}", Arrays.toString(urls));
        }
    }

    @Override
    public Optional<MappingDefinition> findMapping(ServletRequest request) {
        return findMapping(request.getPath(), request.getMethod());
    }

    @Override
    public Optional<MappingDefinition> findMapping(String path, String method) {
        if (StringUtils.isEmpty(path) || StringUtils.isEmpty(method)) {
            return Optional.empty();
        }

        // 标准化路径
        String normalizedPath = normalizePath(path);
        String key = method.toUpperCase() + ":" + normalizedPath;

        // 首先尝试精确匹配
        MappingDefinition exactMapping = exactMappings.get(key);
        if (exactMapping != null) {
            if (log.isDebugEnabled()) {
                log.debug("找到精确匹配的映射: {} {}", method, normalizedPath);
            }
            return Optional.of(exactMapping);
        }

        // 尝试模式匹配
        for (PatternMapping patternMapping : patternMappings) {
            if (patternMapping.method.equalsIgnoreCase(method) &&
                    patternMapping.pattern.matcher(normalizedPath).matches()) {
                if (log.isDebugEnabled()) {
                    log.debug("找到模式匹配的映射: {} {} -> {}", method, normalizedPath, patternMapping.originalPath);
                }
                return Optional.of(patternMapping.mappingDefinition);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("未找到匹配的映射: {} {}", method, normalizedPath);
        }
        return Optional.empty();
    }

    @Override
    public List<MappingDefinition> getAllMappings() {
        return new ArrayList<>(allMappings);
    }

    @Override
    public void clear() {
        exactMappings.clear();
        patternMappings.clear();
        allMappings.clear();
        if (log.isDebugEnabled()) {
            log.debug("清空所有映射定义");
        }
    }

    @Override
    public int size() {
        return allMappings.size();
    }

    @Override
    public boolean isEmpty() {
        return allMappings.isEmpty();
    }

    @Override
    public void executeMapping(MappingDefinition mappingDefinition, ServletRequest request,
            ServletResponse response) throws Exception {
        if (mappingDefinition == null) {
            throw new IllegalArgumentException("映射定义不能为null");
        }

        MethodDescribe methodDescribe = mappingDefinition.getMethodDescribe();
        
        // 支持 ServletHandlerMethodDescribe（Lambda 表达式形式的路由注册）
        if (methodDescribe instanceof ServletHandlerMethodDescribe handlerMethodDescribe) {
            if (log.isDebugEnabled()) {
                log.debug("执行 ServletHandler: path={}", String.join(",", mappingDefinition.url()));
            }
            handlerMethodDescribe.invoke(request, response);
            return;
        }

        if (methodDescribe == null) {
            throw new IllegalStateException("映射定义中的方法描述为null");
        }

        BeanDefinition beanDefinition = mappingDefinition.getBeanDefinition();
        if (beanDefinition == null) {
            throw new IllegalStateException("映射定义中的Bean定义为null");
        }
        Object targetObject = beanDefinition.getBean();
        if (targetObject == null) {
            // 如果Bean未初始化，尝试从ObjectContext获取
            targetObject = objectContext.getBean(beanDefinition.getName(), beanDefinition.getBeanClass());
        }
        if (targetObject == null) {
            throw new IllegalStateException("映射定义中的目标对象为null");
        }

        Method method = methodDescribe.getMethod();
        if (method == null) {
            throw new IllegalStateException("映射定义中的方法为null");
        }

        if (log.isDebugEnabled()) {
            log.debug("执行映射方法: {}.{}", targetObject.getClass().getSimpleName(), method.getName());
        }

        try {
            // 准备方法参数
            Object[] args = prepareMethodArguments(method, request, response);

            // 调用方法
            Object result = method.invoke(targetObject, args);

            // 处理返回值
            handleMethodResult(result, response);

        } catch (Exception e) {
            log.error("执行映射方法失败: {}.{}", targetObject.getClass().getSimpleName(), method.getName(), e);
            throw e;
        }
    }

    /**
     * 注册单个映射
     */
    private void registerSingleMapping(String url, String method, MappingDefinition mappingDefinition) {
        String normalizedUrl = normalizePath(url);

        if (isPatternPath(normalizedUrl)) {
            // 路径包含参数，使用模式匹配
            Pattern pattern = createPathPattern(normalizedUrl);
            patternMappings.add(new PatternMapping(normalizedUrl, method, pattern, mappingDefinition));
        } else {
            // 精确路径，使用精确匹配
            String key = method + ":" + normalizedUrl;
            exactMappings.put(key, mappingDefinition);
        }
    }

    /**
     * 标准化路径
     */
    private String normalizePath(String path) {
        if (StringUtils.isEmpty(path)) {
            return "/";
        }

        // 确保以/开头
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // 移除末尾/（除非是根路径）
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    /**
     * 检查路径是否包含参数
     */
    private boolean isPatternPath(String path) {
        return path.contains("{") && path.contains("}");
    }

    /**
     * 创建路径模式
     */
    private Pattern createPathPattern(String path) {
        // 将{param} 替换为([^/]+)
        String regex = path.replaceAll("\\{[^}]+\\}", "([^/]+)");
        return Pattern.compile("^" + regex + "$");
    }

    /**
     * 准备方法参数
     */
    private Object[] prepareMethodArguments(Method method, ServletRequest request, ServletResponse response) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];

            if (ServletRequest.class.isAssignableFrom(paramType)) {
                args[i] = request;
            } else if (ServletResponse.class.isAssignableFrom(paramType)) {
                args[i] = response;
            } else {
                // 其他参数类型暂时设为null，后续可以扩展
                args[i] = null;
            }
        }

        return args;
    }

    /**
     * 处理方法返回值
     */
    private void handleMethodResult(Object result, ServletResponse response) {
        if (result == null) {
            return;
        }

        if (result instanceof String) {
            response.setBodyString((String) result);
            if (response.getContentType() == null) {
                response.setContentType("text/plain");
            }
        } else {
            // 其他类型转换为JSON字符串
            response.setBodyString(result.toString());
            if (response.getContentType() == null) {
                response.setContentType("application/json");
            }
        }

        // 确保状态码已设置
        if (response.getStatusCode() == 0) {
            response.setStatusCode(200);
        }
    }

    /**
     * 模式映射内部类
     */
    private static class PatternMapping {
        final String originalPath;
        final String method;
        final Pattern pattern;
        final MappingDefinition mappingDefinition;

        PatternMapping(String originalPath, String method, Pattern pattern, MappingDefinition mappingDefinition) {
            this.originalPath = originalPath;
            this.method = method;
            this.pattern = pattern;
            this.mappingDefinition = mappingDefinition;
        }
    }
}