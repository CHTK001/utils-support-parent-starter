package com.chua.common.support.network.protocol.server;

import com.chua.common.support.base.collection.Options;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认Servlet过滤器配置实现
 * <p>
 * 提供过滤器配置信息的默认实现，包含过滤器名称、初始化参数、服务器上下文等。
 * 支持动态配置更新和参数类型转换。
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
public class DefaultServletFilterConfig implements ServletFilterConfig {

    /**
     * 过滤器名称
     */
    private final String filterName;

    /**
     * 服务器上下文
     */
    private final ServletContext servletContext;

    /**
     * 初始化参数映射
     */
    private final Map<String, String> initParameters;

    /**
     * 构造函数
     *
     * @param filterName 过滤器名称
     * @param servletContext 服务器上下文
     */
    public DefaultServletFilterConfig(String filterName, ServletContext servletContext) {
        this(filterName, servletContext, new Options());
    }

    /**
     * 构造函数
     *
     * @param filterName 过滤器名称
     * @param servletContext 服务器上下文
     * @param options 配置选项
     */
    public DefaultServletFilterConfig(String filterName, ServletContext servletContext, Options options) {
        this.filterName = filterName != null ? filterName : "UnknownFilter";
        this.servletContext = servletContext != null ? servletContext : new DefaultServletContext();
        this.initParameters = new ConcurrentHashMap<>();
        
        // 从Options中提取初始化参数
        if (options != null) {
            options.forEach((key, value) -> {
                if (key != null && value != null) {
                    initParameters.put(key, value.toString());
                }
            });
        }
    }

    @Override
    public String getFilterName() {
        return filterName;
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
        return new HashMap<>(initParameters);
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public void setInitParameter(String name, String value) {
        if (name != null) {
            if (value != null) {
                initParameters.put(name, value);
            } else {
                initParameters.remove(name);
            }
        }
    }

    @Override
    public void removeInitParameter(String name) {
        if (name != null) {
            initParameters.remove(name);
        }
    }

    // ========== 扩展方法 ==========

    /**
     * 获取布尔类型参数
     *
     * @param name 参数名称
     * @param defaultValue 默认值
     * @return 参数值
     */
    public boolean getBooleanParameter(String name, boolean defaultValue) {
        String value = getInitParameter(name);
        if (value != null) {
            try {
                return Boolean.parseBoolean(value);
            } catch (Exception e) {
                log.warn("无法解析布尔参数 {}: {}, 使用默认值: {}", name, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 获取整数类型参数
     *
     * @param name 参数名称
     * @param defaultValue 默认值
     * @return 参数值
     */
    public int getIntParameter(String name, int defaultValue) {
        String value = getInitParameter(name);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("无法解析整数参数 {}: {}, 使用默认值: {}", name, value, defaultValue);
            }
        }
        return defaultValue;
    }

    @Override
    public String getStringParameter(String name, String defaultValue){
        return getInitParameter(name);
    }

    /**
     * 获取长整数类型参数
     *
     * @param name 参数名称
     * @param defaultValue 默认值
     * @return 参数值
     */
    public long getLongParameter(String name, long defaultValue) {
        String value = getInitParameter(name);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("无法解析长整数参数 {}: {}, 使用默认值: {}", name, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 获取双精度浮点数类型参数
     *
     * @param name 参数名称
     * @param defaultValue 默认值
     * @return 参数值
     */
    public double getDoubleParameter(String name, double defaultValue) {
        String value = getInitParameter(name);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                log.warn("无法解析双精度参数 {}: {}, 使用默认值: {}", name, value, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 获取字符串参数（带默认值）
     *
     * @param name 参数名称
     * @param defaultValue 默认值
     * @return 参数值
     */
    public String getParameter(String name, String defaultValue) {
        String value = getInitParameter(name);
        return value != null ? value : defaultValue;
    }

    /**
     * 添加多个初始化参数
     *
     * @param parameters 参数映射
     */
    public void addInitParameters(Map<String, String> parameters) {
        if (parameters != null) {
            initParameters.putAll(parameters);
        }
    }

    /**
     * 清空所有初始化参数
     */
    public void clearInitParameters() {
        initParameters.clear();
    }

    /**
     * 获取参数数量
     *
     * @return 参数数量
     */
    public int getParameterCount() {
        return initParameters.size();
    }

    /**
     * 检查是否包含指定参数
     *
     * @param name 参数名称
     * @return 如果包含返回true，否则返回false
     */
    public boolean hasParameter(String name) {
        return initParameters.containsKey(name);
    }

    @Override
    public String toString() {
        return "DefaultServletFilterConfig{" +
                "filterName='" + filterName + '\'' +
                ", parameterCount=" + initParameters.size() +
                ", servletContext=" + servletContext +
                '}';
    }
}