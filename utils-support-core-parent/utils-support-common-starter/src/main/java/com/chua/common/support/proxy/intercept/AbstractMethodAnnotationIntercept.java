package com.chua.common.support.proxy.intercept;

import com.chua.common.support.expression.ExpressionResolvers;
import com.chua.common.support.lang.placeholder.StringValuePropertyResolver;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 方法注解拦截器抽象基类，统一处理 #{...} 表达式、${...} 占位符和默认值解析。
 *
 * <p>子类拦截器继承本基类后，无需重复实现属性解析逻辑，只需实现
 * {@link com.chua.common.support.proxy.annotation.MethodAnnotationIntercept} 的核心拦截方法。</p>
 *
 * <p><b>属性解析链（按优先级）：</b></p>
 * <ol>
 *   <li>{@code #{...}} 表达式，通过 {@link ExpressionResolvers} SPI 链解析，
 *       支持默认变量访问（common）和 SpEL 全特性（spring 增强）</li>
 *   <li>{@code ${...}} 占位符，通过 {@link StringValuePropertyResolver} 从配置源读取</li>
 *   <li>普通字面量，直接使用</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractMethodAnnotationIntercept {

    /**
     * 占位符解析器，用于解析 ${...} 格式的占位符
     */
    protected final StringValuePropertyResolver propertyResolver = new StringValuePropertyResolver(null);

    /**
     * 解析整型属性值。
     *
     * <p>属性值可能是表达式、占位符或字面量，解析失败时使用默认值。</p>
     *
     * @param value        注解属性原始值
     * @param defaultValue 解析失败时的默认值
     * @param proxyMethod  被拦截方法信息，用于表达式求值上下文
     * @return 解析后的整型值
     */
    protected int resolveInt(String value, int defaultValue, ProxyMethod proxyMethod) {
        String resolved = resolve(value, proxyMethod);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析长整型属性值，支持语义别名。
     *
     * @param value        主属性值
     * @param alias        别名属性值，主属性为空时使用
     * @param defaultValue 解析失败时的默认值
     * @param proxyMethod  被拦截方法信息，用于表达式求值上下文
     * @return 解析后的长整型值
     */
    protected long resolveLong(String value, String alias, long defaultValue, ProxyMethod proxyMethod) {
        String actual = StringUtils.hasText(alias) ? alias : value;
        return resolveLong(actual, defaultValue, proxyMethod);
    }

    /**
     * 解析长整型属性值。
     *
     * @param value        注解属性原始值
     * @param defaultValue 解析失败时的默认值
     * @param proxyMethod  被拦截方法信息，用于表达式求值上下文
     * @return 解析后的长整型值
     */
    protected long resolveLong(String value, long defaultValue, ProxyMethod proxyMethod) {
        String resolved = resolve(value, proxyMethod);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析双精度属性值。
     *
     * @param value        注解属性原始值
     * @param defaultValue 解析失败时的默认值
     * @param proxyMethod  被拦截方法信息，用于表达式求值上下文
     * @return 解析后的双精度值
     */
    protected double resolveDouble(String value, double defaultValue, ProxyMethod proxyMethod) {
        String resolved = resolve(value, proxyMethod);
        if (resolved == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(resolved);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析名称属性，支持表达式和占位符。
     *
     * <p>标注的 name 为空时，使用 {@code 目标类全限定名.方法名} 作为兜底唯一标识。
     * name 支持表达式时，可实现参数级动态隔离（如 {@code #{method.name + '-' + args[0]}}）。</p>
     *
     * @param annotatedName 注解中的 name 值
     * @param proxyMethod   被拦截方法信息
     * @return 解析后的名称
     */
    protected String resolveName(String annotatedName, ProxyMethod proxyMethod) {
        if (!StringUtils.hasText(annotatedName)) {
            return proxyMethod.getTarget().getClass().getName() + "." + proxyMethod.getMethod().getName();
        }
        String resolved = resolve(annotatedName, proxyMethod);
        return resolved != null ? resolved : annotatedName;
    }

    /**
     * 解析属性文本，按优先级依次尝试表达式、占位符、字面量。
     *
     * <ol>
     *   <li>文本含 {@code #{...}} 时，通过 {@link ExpressionResolvers} SPI 链求值</li>
     *   <li>文本含 {@code ${...}} 时，交给 {@link StringValuePropertyResolver} 解析占位符</li>
     *   <li>其余情况直接返回原文（字面量）</li>
     * </ol>
     *
     * @param text        注解属性原始文本
     * @param proxyMethod 被拦截方法信息
     * @return 解析后的值，无法解析时返回 null
     */
    protected String resolve(String text, ProxyMethod proxyMethod) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.contains("#{") && text.contains("}")) {
            int start = text.indexOf("#{");
            int end = text.indexOf("}", start);
            if (start >= 0 && end > start) {
                String expr = text.substring(start, end + 1);
                String resolved = ExpressionResolvers.resolve(expr, createRoot(proxyMethod), createVariables(proxyMethod));
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        if (text.contains("${")) {
            return propertyResolver.resolvePlaceholders(text);
        }
        return text;
    }

    /**
     * 创建表达式求值的根对象。
     *
     * @param proxyMethod 被拦截方法信息
     * @return 目标实例作为根对象
     */
    protected Object createRoot(ProxyMethod proxyMethod) {
        return proxyMethod != null ? proxyMethod.getTarget() : null;
    }

    /**
     * 创建表达式求值的上下文变量。
     *
     * <p>注入 {@code method}、{@code args}、{@code targetClass} 三个变量，
     * 供表达式访问方法信息和入参。</p>
     *
     * @param proxyMethod 被拦截方法信息
     * @return 上下文变量映射
     */
    protected Map<String, Object> createVariables(ProxyMethod proxyMethod) {
        Map<String, Object> variables = new HashMap<>(4);
        if (proxyMethod != null) {
            variables.put("method", proxyMethod.getMethod());
            variables.put("args", proxyMethod.getArgs() != null ? proxyMethod.getArgs() : new Object[0]);
            variables.put("targetClass", proxyMethod.getTarget().getClass());
            variables.put("target", proxyMethod.getTarget());
        }
        return variables;
    }
}