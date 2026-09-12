package com.chua.common.support.spi;

import com.chua.common.support.spi.annotations.ConditionalOnClass;
import com.chua.common.support.spi.annotations.ConditionalOnMissingClass;
import com.chua.common.support.spi.annotations.ConditionalOnProperty;
import com.chua.common.support.spi.definition.ServiceDefinition;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 条件评估器，用于评估服务定义是否满足加载条件。
 *
 * <p>该类负责评估服务定义上的条件注解，包括：</p>
 * <ul>
 *   <li>{@link ConditionalOnProperty} - 基于系统属性的条件</li>
 *   <li>{@link ConditionalOnClass} - 基于类存在性的条件</li>
 *   <li>{@link ConditionalOnMissingClass} - 基于类缺失的条件</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * ConditionEvaluator evaluator = new ConditionEvaluator();
 * ServiceDefinition definition = ...;
 * if (evaluator.evaluate(definition)) {
 *     // 条件满足，可以加载服务
 * }
 * }</pre>以加载服务
 * }
 * }</pre>
 *
 * @author CH
 * @since 1.0
 * @see ServiceDefinition
 * @see ConditionalOnProperty
 * @see ConditionalOnClass
 * @see ConditionalOnMissingClass
 */
@Slf4j
public class ConditionEvaluator {


    /** 创建 条件evaluator 实例 */
    public ConditionEvaluator() {
    }

    /**
     * 评估服务定义是否满足条件
     *
     * @param definition 服务定义
     * @return true 表示满足条件
     */
    public boolean evaluate(ServiceDefinition definition) {
        if (definition == null || definition.getImplClass() == null) {
            return false;
        }

        Class<?> implClass = definition.getImplClass();

        if (!evaluatePropertyCondition(implClass)) {
            return false;
        }

        if (!evaluateClassCondition(implClass)) {
            return false;
        }

        if (!evaluateMissingClassCondition(implClass)) {
            return false;
        }

        return true;
    }

    /**
     * 评估属性条件
     *
     * @param implClass 实现类
     * @return true 表示满足条件
     */
    private boolean evaluatePropertyCondition(Class<?> implClass) {
        ConditionalOnProperty condition = implClass.getAnnotation(ConditionalOnProperty.class);
        if (condition == null) {
            return true;
        }

        String propertyName = condition.name();
        String expectedValue = condition.value();
        String defaultValue = condition.defaultValue();
        boolean matchValue = condition.matchValue();
        boolean ignoreCase = condition.ignoreCase();

        String actualValue = System.getProperty(propertyName, defaultValue);

        if (actualValue == null || actualValue.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("属性 {} 未配置", propertyName);
            }
            return false;
        }

        if (!matchValue) {
            return true;
        }

        if (expectedValue.isEmpty()) {
            return true;
        }

        boolean matches = ignoreCase ?
                expectedValue.equalsIgnoreCase(actualValue) :
                expectedValue.equals(actualValue);

        if (!matches) {
            if (log.isDebugEnabled()) {
                log.debug("属性 {} 期望值 {} 与实际值 {} 不匹配", propertyName, expectedValue, actualValue);
            }
        }

        return matches;
    }

    /**
     * 评估类存在条件
     *
     * @param implClass 实现类
     * @return true 表示满足条件
     */
    private boolean evaluateClassCondition(Class<?> implClass) {
        ConditionalOnClass condition = implClass.getAnnotation(ConditionalOnClass.class);
        if (condition == null) {
            return true;
        }

        for (String className : condition.value()) {
            if (!ClassUtils.isPresent(className)) {
                if (log.isDebugEnabled()) {
                    log.debug("依赖类 {} 不存在", className);
                }
                return false;
            }
        }

        for (Class<?> clazz : condition.classes()) {
            if (!ClassUtils.isPresent(clazz.getName())) {
                if (log.isDebugEnabled()) {
                    log.debug("依赖类 {} 不存在", clazz.getName());
                }
                return false;
            }
        }

        return true;
    }

    /**
     * 评估类缺失条件
     *
     * @param implClass 实现类
     * @return true 表示满足条件
     */
    private boolean evaluateMissingClassCondition(Class<?> implClass) {
        ConditionalOnMissingClass condition = implClass.getAnnotation(ConditionalOnMissingClass.class);
        if (condition == null) {
            return true;
        }

        for (String className : condition.value()) {
            if (ClassUtils.isPresent(className)) {
                if (log.isDebugEnabled()) {
                    log.debug("依赖类 {} 不应存在但找到了", className);
                }
                return false;
            }
        }

        for (Class<?> clazz : condition.classes()) {
            if (ClassUtils.isPresent(clazz.getName())) {
                if (log.isDebugEnabled()) {
                    log.debug("依赖类 {} 不应存在但找到了", clazz.getName());
                }
                return false;
            }
        }

        return true;
    }
}
