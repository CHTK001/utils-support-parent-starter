package com.chua.common.support.network.protocol.server;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认Servlet上下文实现
 * <p>
 * 提供服务器上下文信息的默认实现，包含服务器信息、属性管理、初始化参数等功能。
 * 是线程安全的，内部使用 {@link ConcurrentHashMap} 存储属性和初始化参数。
 * <p>
 * 支持通过 {@link Builder} 模式灵活构建实例。
 *
 * @author CH
 * @version 1.1
 * @since 2024/7/8
 */
@Slf4j
public class DefaultServletContext implements ServletContext {

    private static final String DEFAULT_CONTEXT_PATH = "/";
    private static final String DEFAULT_SERVER_INFO = "Protocol Server";
    private static final int DEFAULT_MAJOR_VERSION = 2;
    private static final int DEFAULT_MINOR_VERSION = 0;

    private final String contextPath;
    private final String serverInfo;
    private final int majorVersion;
    private final int minorVersion;
    private final ConcurrentHashMap<String, Object> attributes;
    private final ConcurrentHashMap<String, String> initParameters;
    private final AtomicReference<Map<String, Object>> attributesViewCache = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> initParametersViewCache = new AtomicReference<>();

    public DefaultServletContext() {
        this(DEFAULT_CONTEXT_PATH, DEFAULT_SERVER_INFO);
    }

    public DefaultServletContext(String contextPath, String serverInfo) {
        this(contextPath, serverInfo, DEFAULT_MAJOR_VERSION, DEFAULT_MINOR_VERSION);
    }

    public DefaultServletContext(String contextPath, String serverInfo, int majorVersion, int minorVersion) {
        if (majorVersion < 0 || minorVersion < 0) {
            throw new IllegalArgumentException("版本号不能为负数: majorVersion=" + majorVersion + ", minorVersion=" + minorVersion);
        }
        this.contextPath = contextPath != null ? contextPath : DEFAULT_CONTEXT_PATH;
        this.serverInfo = serverInfo != null ? serverInfo : DEFAULT_SERVER_INFO;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.attributes = new ConcurrentHashMap<>();
        this.initParameters = new ConcurrentHashMap<>();
        if (log.isDebugEnabled()) {
            log.debug("初始化ServletContext: contextPath={}, serverInfo={}, version={}.{}", this.contextPath, this.serverInfo, majorVersion, minorVersion);
        }
    }

    private DefaultServletContext(Builder builder) {
        this(builder.contextPath, builder.serverInfo, builder.majorVersion, builder.minorVersion);
        if (builder.initParameters != null) {
            this.initParameters.putAll(builder.initParameters);
        }
        if (builder.attributes != null) {
            this.attributes.putAll(builder.attributes);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public String getServerInfo() {
        return serverInfo;
    }

    @Override
    public int getMajorVersion() {
        return majorVersion;
    }

    @Override
    public int getMinorVersion() {
        return minorVersion;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (name == null) {
            log.warn("尝试设置属性时，属性名称为null，操作被忽略");
            return;
        }
        invalidateAttributesCache();
        if (value != null) {
            Object oldValue = attributes.put(name, value);
            if (log.isDebugEnabled()) {
                log.debug("设置属性: {} = {}, 旧值: {}", name, value, oldValue);
            }
        } else {
            Object removed = attributes.remove(name);
            if (log.isDebugEnabled()) {
                log.debug("移除属性: {}, 被移除的值: {}", name, removed);
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        if (name == null) {
            log.warn("尝试移除属性时，属性名称为null，操作被忽略");
            return;
        }
        invalidateAttributesCache();
        Object removed = attributes.remove(name);
        if (log.isDebugEnabled()) {
            log.debug("移除属性: {}, 被移除的值: {}", name, removed);
        }
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }

    @Override
    public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(new HashMap<>(initParameters));
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    @Override
    public void log(String message) {
        log.info("[ServletContext] {}", message);
    }

    @Override
    public void log(String message, Throwable throwable) {
        log.error("[ServletContext] {}", message, throwable);
    }

    public void setInitParameter(String name, String value) {
        if (name == null) {
            log.warn("尝试设置初始化参数时，参数名称为null，操作被忽略");
            return;
        }
        invalidateInitParametersCache();
        if (value != null) {
            String oldValue = initParameters.put(name, value);
            if (log.isDebugEnabled()) {
                log.debug("设置初始化参数: {} = {}, 旧值: {}", name, value, oldValue);
            }
        } else {
            String removed = initParameters.remove(name);
            if (log.isDebugEnabled()) {
                log.debug("移除初始化参数: {}, 被移除的值: {}", name, removed);
            }
        }
    }

    public void addInitParameters(Map<String, String> parameters) {
        if (parameters != null && !parameters.isEmpty()) {
            invalidateInitParametersCache();
            initParameters.putAll(parameters);
            if (log.isDebugEnabled()) {
                log.debug("批量添加初始化参数，数量: {}", parameters.size());
            }
        }
    }

    public void addAttributes(Map<String, Object> attributes) {
        if (attributes != null && !attributes.isEmpty()) {
            invalidateAttributesCache();
            this.attributes.putAll(attributes);
            if (log.isDebugEnabled()) {
                log.debug("批量添加属性，数量: {}", attributes.size());
            }
        }
    }

    public boolean containsAttribute(String name) {
        return attributes.containsKey(name);
    }

    public boolean containsInitParameter(String name) {
        return initParameters.containsKey(name);
    }

    public int getAttributeCount() {
        return attributes.size();
    }

    public int getInitParameterCount() {
        return initParameters.size();
    }

    public int clearAttributes() {
        invalidateAttributesCache();
        Map<String, Object> removed = new HashMap<>();
        attributes.forEach((k, v) -> removed.put(k, attributes.remove(k)));
        int size = removed.size();
        if (log.isDebugEnabled()) {
            log.debug("清空所有属性，共清除: {} 个", size);
        }
        return size;
    }

    public int clearInitParameters() {
        invalidateInitParametersCache();
        Map<String, String> removed = new HashMap<>();
        initParameters.forEach((k, v) -> removed.put(k, initParameters.remove(k)));
        int size = removed.size();
        if (log.isDebugEnabled()) {
            log.debug("清空所有初始化参数，共清除: {} 个", size);
        }
        return size;
    }

    public boolean isAttributesEmpty() {
        return attributes.isEmpty();
    }

    public boolean isInitParametersEmpty() {
        return initParameters.isEmpty();
    }

    public Object getAttributeOrDefault(String name, Object defaultValue) {
        return attributes.getOrDefault(name, defaultValue);
    }

    public String getInitParameterOrDefault(String name, String defaultValue) {
        return initParameters.getOrDefault(name, defaultValue);
    }

    public Object putAttributeIfAbsent(String name, Object value) {
        if (name == null || value == null) {
            return null;
        }
        Object existing = attributes.putIfAbsent(name, value);
        if (existing == null) {
            invalidateAttributesCache();
            if (log.isDebugEnabled()) {
                log.debug("设置属性(如果不存在): {} = {}", name, value);
            }
        }
        return existing;
    }

    public String putInitParameterIfAbsent(String name, String value) {
        if (name == null || value == null) {
            return null;
        }
        String existing = initParameters.putIfAbsent(name, value);
        if (existing == null) {
            invalidateInitParametersCache();
            if (log.isDebugEnabled()) {
                log.debug("设置初始化参数(如果不存在): {} = {}", name, value);
            }
        }
        return existing;
    }

    public Object computeAttributeIfAbsent(String name, Function<String, Object> mappingFunction) {
        if (name == null) {
            return null;
        }
        return attributes.computeIfAbsent(name, k -> {
            invalidateAttributesCache();
            Object value = mappingFunction.apply(k);
            if (log.isDebugEnabled()) {
                log.debug("计算并设置属性: {} = {}", name, value);
            }
            return value;
        });
    }

    private void invalidateAttributesCache() {
        attributesViewCache.set(null);
    }

    private void invalidateInitParametersCache() {
        initParametersViewCache.set(null);
    }

    @Override
    public String toString() {
        return "DefaultServletContext{" +
                "contextPath='" + contextPath + '\'' +
                ", serverInfo='" + serverInfo + '\'' +
                ", majorVersion=" + majorVersion +
                ", minorVersion=" + minorVersion +
                ", attributeCount=" + attributes.size() +
                ", initParameterCount=" + initParameters.size() +
                '}';
    }

    public static class Builder {
        private String contextPath = DEFAULT_CONTEXT_PATH;
        private String serverInfo = DEFAULT_SERVER_INFO;
        private int majorVersion = DEFAULT_MAJOR_VERSION;
        private int minorVersion = DEFAULT_MINOR_VERSION;
        private Map<String, String> initParameters;
        private Map<String, Object> attributes;

        public Builder contextPath(String contextPath) {
            this.contextPath = contextPath;
            return this;
        }

        public Builder serverInfo(String serverInfo) {
            this.serverInfo = serverInfo;
            return this;
        }

        public Builder majorVersion(int majorVersion) {
            this.majorVersion = majorVersion;
            return this;
        }

        public Builder minorVersion(int minorVersion) {
            this.minorVersion = minorVersion;
            return this;
        }

        public Builder version(int majorVersion, int minorVersion) {
            this.majorVersion = majorVersion;
            this.minorVersion = minorVersion;
            return this;
        }

        public Builder initParameter(String name, String value) {
            if (this.initParameters == null) {
                this.initParameters = new HashMap<>();
            }
            this.initParameters.put(name, value);
            return this;
        }

        public Builder initParameters(Map<String, String> parameters) {
            if (parameters != null) {
                if (this.initParameters == null) {
                    this.initParameters = new HashMap<>();
                }
                this.initParameters.putAll(parameters);
            }
            return this;
        }

        public Builder attribute(String name, Object value) {
            if (this.attributes == null) {
                this.attributes = new HashMap<>();
            }
            this.attributes.put(name, value);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                if (this.attributes == null) {
                    this.attributes = new HashMap<>();
                }
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public DefaultServletContext build() {
            return new DefaultServletContext(this);
        }
    }
}