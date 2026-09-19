package com.chua.common.support.objects.lifecycle;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.BeanScope;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

/**
 * Bean 生命周期管理器，统一调度所有 Beandefinitionlifecycle SPI 实现。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public class BeanDefinitionLifecycleManager {

    /**
     * 获取所有的生命周期处理器列表。
     *
     * @return 生命周期处理器列表
     */
    private static List<BeanDefinitionLifecycle> getLifecycleHandlers() {
        return ServiceProvider.of(BeanDefinitionLifecycle.class).collect();
    }

    /**
     * 初始化指定的 Bean 实例。
     *
     * @param beanDefinition 定义信息
     * @param bean           Bean 实例
     */
    public static void init(BeanDefinition beanDefinition, Object bean) {
        if (beanDefinition == null || bean == null) {
            return;
        }
        for (BeanDefinitionLifecycle lifecycle : getLifecycleHandlers()) {
            if (lifecycle.isSupport(beanDefinition)) {
                try {
                    lifecycle.init(beanDefinition, bean);
                } catch (Exception e) {
                    log.error("初始化 Bean 失败：{} Bean: {}", lifecycle.getClass().getSimpleName(), beanDefinition.getName(), e);
                }
            }
        }
    }

    /**
     * 销毁指定的 Bean 实例。
     *
     * @param beanDefinition 定义信息
     * @param bean           Bean 实例
     */
    public static void destroy(BeanDefinition beanDefinition, Object bean) {
        if (beanDefinition == null || bean == null) {
            return;
        }
        for (BeanDefinitionLifecycle lifecycle : getLifecycleHandlers()) {
            if (lifecycle.isSupport(beanDefinition)) {
                try {
                    lifecycle.destroy(beanDefinition, bean);
                } catch (Exception e) {
                    log.error("销毁 Bean 失败：{} Bean: {}", lifecycle.getClass().getSimpleName(), beanDefinition.getName(), e);
                }
            }
        }
    }

    /**
     * 批量销毁指定的 Bean 集合。
     *
     * @param beanDefinitions Bean 定义集合
     */
    public static void destroyAll(Collection<BeanDefinition> beanDefinitions) {
        if (beanDefinitions == null || beanDefinitions.isEmpty()) {
            return;
        }
        for (BeanDefinition def : beanDefinitions) {
            Object bean = def.getBean();
            if (bean != null) {
                destroy(def, bean);
            }
        }
    }

    /**
     * 判断指定的 Bean 是否为单例模式。
     *
     * @param beanDefinition Bean 定义信息
     * @return 如果是单例返回 true，否则返回 false
     */
    public static boolean isSingleton(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return false;
        }
        for (BeanDefinitionLifecycle lifecycle : getLifecycleHandlers()) {
            if (lifecycle.isSupport(beanDefinition)) {
                return lifecycle.isSingleton(beanDefinition);
            }
        }
        BeanScope scope = beanDefinition.getScope();
        return scope == null || BeanScope.SINGLETON == scope;
    }
}
