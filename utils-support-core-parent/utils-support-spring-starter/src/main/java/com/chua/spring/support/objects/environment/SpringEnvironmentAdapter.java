package com.chua.spring.support.objects.environment;

import com.chua.common.support.objects.environment.Environment;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.util.PropertyPlaceholderHelper;

/**
* Spring {@link org.springframework.core.env.Environment} 适配器，包装框架 {@link Environment}。
*
* <p>所有 {@code getProperty}/{@code resolvePlaceholders} 均委托框架 {@link Environment}，
* 使 Spring {@code @Value} 解析时直接使用框架已聚合的配置源（含 {@code SpringConfigSourceProvider}）。</p>
*
* @author CH
* @since 2024/12/20
 */
@RequiredArgsConstructor
public class SpringEnvironmentAdapter implements org.springframework.core.env.Environment {

    /**
    * Spring 属性占位符解析器
    */
    private static final PropertyPlaceholderHelper HELPER =
            /**
             * PropertyPlaceholderHelper。
             *
             * @param null 方法入参 null
             * @param true 方法入参 true
             * @return 结果值
             */
            new PropertyPlaceholderHelper("${", "}", ":", null, true);

    /**
    * 被委托的框架环境
    */
    @Getter
    /** Delegate */
    private final Environment delegate;

    @Override
    /**
    * 获取财产
    * @param key 键
    */
    public String getProperty(String key) {
        return delegate.getProperty(key);
    }

    @Override
    /**
    * 获取财产
    * @param key 键
    * @param defaultValue 默认值
    */
    public String getProperty(String key, String defaultValue) {
        return delegate.getProperty(key, defaultValue);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType) {
        return delegate.getProperty(key, targetType);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        return delegate.getProperty(key, targetType, defaultValue);
    }

    @Override
    /**
    * 获取required财产
    * @param key 键
    */
    public String getRequiredProperty(String key) throws IllegalStateException {
        String value = delegate.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("缺少必要配置: " + key);
        }
        return value;
    }

    @Override
    public <T> T getRequiredProperty(String key, Class<T> targetType) throws IllegalStateException {
        T value = delegate.getProperty(key, targetType);
        if (value == null) {
            throw new IllegalStateException("缺少必要配置: " + key);
        }
        return value;
    }

    @Override
    /**
    * 解析Placeholders
    * @param text 文本
    */
    public String resolvePlaceholders(String text) {
        return HELPER.replacePlaceholders(text, key -> {
            Object value = delegate.getProperty(key);
            return value != null ? String.valueOf(value) : null;
        });
    }

    @Override
    /**
    * 解析requiredplaceholders
    * @param text 文本
    */
    public String resolveRequiredPlaceholders(String text) throws IllegalArgumentException {
        String resolved = resolvePlaceholders(text);
        if (resolved != null && resolved.contains("${")) {
            throw new IllegalArgumentException("无法解析的占位符: " + text);
        }
        return resolved;
    }

    @Override
    /**
    * contains财产
    * @param key 键
    */
    public boolean containsProperty(String key) {
        return delegate.containsProperty(key);
    }

    @Override
    /** 获取活跃配置文件 */
    public String[] getActiveProfiles() {
        return new String[0];
    }

    @Override
    /** 获取默认配置文件 */
    public String[] getDefaultProfiles() {
        return new String[0];
    }

    @Override
    /**
    * accepts配置文件
    * @param profiles 配置文件
    */
    public boolean acceptsProfiles(String... profiles) {
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    /**
    * accepts配置文件
    * @param profiles 配置文件
    * @return accepts配置文件的结果
    */
    public boolean acceptsProfiles(org.springframework.core.env.Profiles profiles) {
        return false;
    }
}
