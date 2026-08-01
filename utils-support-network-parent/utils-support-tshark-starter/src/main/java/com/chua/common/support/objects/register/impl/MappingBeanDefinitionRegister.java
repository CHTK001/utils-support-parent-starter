package com.chua.common.support.objects.register.impl;

import com.chua.common.support.objects.definition.MappingDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * URL 路径到 MappingDefinition 的注册器，提供按路径 / 方法的查询与增删。
 *
 * @author CH
 * @since 4.0.0
 */
public class MappingBeanDefinitionRegister {

    /**
     * 已注册的映射定义列表
     */
    private final List<MappingDefinition> mappings = new ArrayList<>();

    /**
     * 按路径与方法查询匹配的映射定义。
     *
     * @param path   请求路径
     * @param method 请求方法（当前仅作占位）
     * @return 命中的 MappingDefinition，未命中返回 Optional.empty()
     */
    public Optional<MappingDefinition> findMappingDefinition(String path, String method) {
        for (MappingDefinition mapping : mappings) {
            Optional<MappingDefinition> found = checkMatch(mapping, path, method);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 判断单个映射是否匹配 path + method。
     *
     * @param mapping 映射定义
     * @param path    请求路径
     * @param method  请求方法
     * @return 命中返回 Optional.of(mapping)，否则返回 empty
     */
    private Optional<MappingDefinition> checkMatch(MappingDefinition mapping, String path, String method) {
        String[] urls = mapping.getUrls();
        for (String url : urls) {
            if (matchUrl(url, path, method)) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    /**
     * 简单路径匹配规则：根路径完全匹配，其他路径做相等判断。
     *
     * @param mappedUrl      已注册的 URL 模板
     * @param requestPath    请求路径
     * @param requestMethod  请求方法（当前忽略）
     * @return true 表示命中
     */
    private boolean matchUrl(String mappedUrl, String requestPath, String requestMethod) {
        if ("/".equals(mappedUrl) && ("/".equals(requestPath) || "".equals(requestPath))) return true;
        return mappedUrl.equals(requestPath);
    }

    /**
     * 设置上下文路径（默认实现为空操作，留作扩展）。
     *
     * @param contextPath 上下文路径
     */
    public void setContextPath(String contextPath) {
    }

    /**
     * 注册一条映射定义。
     *
     * @param mappingDefinition 待注册的映射定义
     */
    public void registerMapping(MappingDefinition mappingDefinition) {
        mappings.add(mappingDefinition);
    }

    /**
     * 批量注册映射定义。
     *
     * @param mappingDefinitions 待注册的映射定义列表
     */
    public void registerMappings(List<MappingDefinition> mappingDefinitions) {
        mappings.addAll(mappingDefinitions);
    }

    /**
     * 按路径移除所有匹配的映射定义。
     *
     * @param path 路径
     */
    public void removeMapping(String path) {
        mappings.removeIf(m -> {
            for (String url : m.getUrls()) {
                if (url.equals(path)) return true;
            }
            return false;
        });
    }

    /**
     * 按路径与方法移除所有匹配的映射定义（当前实现忽略 method 参数）。
     *
     * @param path   路径
     * @param method HTTP 方法
     */
    public void removeMapping(String path, com.chua.common.support.network.http.HttpMethod method) {
        mappings.removeIf(m -> {
            for (String url : m.getUrls()) {
                if (url.equals(path)) return true;
            }
            return false;
        });
    }

    /**
     * 判断是否存在命中该路径的映射定义。
     *
     * @param path 路径
     * @return true 表示已注册
     */
    public boolean hasMapping(String path) {
        return mappings.stream().anyMatch(m -> {
            for (String url : m.getUrls()) {
                if (url.equals(path)) return true;
            }
            return false;
        });
    }

    /**
     * 判断是否存在命中该路径与方法（method 当前忽略）的映射定义。
     *
     * @param path   路径
     * @param method HTTP 方法
     * @return true 表示已注册
     */
    public boolean hasMapping(String path, com.chua.common.support.network.http.HttpMethod method) {
        return hasMapping(path);
    }

    /**
     * @return 已注册的映射定义数量
     */
    public int getMappingCount() {
        return mappings.size();
    }

    /**
     * 清空所有已注册的映射定义。
     */
    public void clearMappings() {
        mappings.clear();
    }
}
