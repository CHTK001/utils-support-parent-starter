package com.chua.common.support.objects.register.impl;

import com.chua.common.support.objects.definition.MappingDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MappingBeanDefinitionRegister {
    private final List<MappingDefinition> mappings = new ArrayList<>();

    public Optional<MappingDefinition> findMappingDefinition(String path, String method) {
        for (MappingDefinition mapping : mappings) {
            Optional<MappingDefinition> found = checkMatch(mapping, path, method);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<MappingDefinition> checkMatch(MappingDefinition mapping, String path, String method) {
        String[] urls = mapping.getUrls();
        for (String url : urls) {
            if (matchUrl(url, path, method)) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    private boolean matchUrl(String mappedUrl, String requestPath, String requestMethod) {
        if ("/".equals(mappedUrl) && ("/".equals(requestPath) || "".equals(requestPath))) return true;
        return mappedUrl.equals(requestPath);
    }

    public void setContextPath(String contextPath) {
    }

    public void registerMapping(MappingDefinition mappingDefinition) {
        mappings.add(mappingDefinition);
    }

    public void registerMappings(List<MappingDefinition> mappingDefinitions) {
        mappings.addAll(mappingDefinitions);
    }

    public void removeMapping(String path) {
        mappings.removeIf(m -> {
            for (String url : m.getUrls()) {
                if (url.equals(path)) return true;
            }
            return false;
        });
    }

    public void removeMapping(String path, com.chua.common.support.network.http.HttpMethod method) {
        mappings.removeIf(m -> {
            for (String url : m.getUrls()) {
                if (url.equals(path)) return true;
            }
            return false;
        });
    }

    public boolean hasMapping(String path) {
        return mappings.stream().anyMatch(m -> {
            for (String url : m.getUrls()) {
                if (url.equals(path)) return true;
            }
            return false;
        });
    }

    public boolean hasMapping(String path, com.chua.common.support.network.http.HttpMethod method) {
        return hasMapping(path);
    }

    public int getMappingCount() {
        return mappings.size();
    }

    public void clearMappings() {
        mappings.clear();
    }
}
