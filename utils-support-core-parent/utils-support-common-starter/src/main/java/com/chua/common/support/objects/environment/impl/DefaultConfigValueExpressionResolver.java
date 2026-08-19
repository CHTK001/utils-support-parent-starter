package com.chua.common.support.objects.environment.impl;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.objects.environment.ConfigValueExpressionResolver;
import com.chua.common.support.objects.environment.Environment;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认配置值表达式解析器，处理 {@code ${key:default}} 占位符格式。
 *
 * <p>解析规则：
 * <ol>
 *   <li>提取 {@code ${}} 包裹的键名</li>
 *   <li>从 {@code :} 分隔默认值（如 {@code ${server.port:8080}}）</li>
 *   <li>调用 {@link Environment#getProperty(String, Class)} 获取值</li>
 *   <li>未找到时回退到默认值</li>
 * </ol></p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("default")
public class DefaultConfigValueExpressionResolver implements ConfigValueExpressionResolver {

    /** Prefix */
    private static final String PREFIX = "${";
    /** Suffix */
    private static final String SUFFIX = "}";
    /** Separator */
    private static final String SEPARATOR = ":";

    @Override
    /** 是否Support */
    public boolean isSupport(String expression) {
        return expression != null && expression.startsWith(PREFIX) && expression.endsWith(SUFFIX);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 解析 */
    public <T> T resolve(String expression, Class<T> targetType, Environment environment) {
        if (expression == null || environment == null) {
            return null;
        }
        String inner = expression.substring(2, expression.length() - 1);
        String key = inner;
        String defaultValue = null;
        int idx = inner.indexOf(SEPARATOR);
        if (idx > 0) {
            key = inner.substring(0, idx);
            defaultValue = inner.substring(idx + 1);
        }
        try {
            T value = environment.getProperty(key, targetType);
            if (value == null && defaultValue != null) {
                if (targetType == String.class) {
                    return (T) defaultValue;
                }
                return Converter.convertIfNecessary(defaultValue, targetType);
            }
            return value;
        } catch (Exception e) {
            log.warn("解析表达式失败: {}", expression, e);
            return null;
        }
    }
}
