package com.chua.common.support.objects.register;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.BeanScope;
import com.chua.common.support.objects.definition.SingletonBeanDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bean 单例注册器，管理单例 Bean 实例的创建和注册。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
public abstract class BeanSingletonRegistry {

    private final Map<String, BeanDefinition> singletonBeans = new ConcurrentHashMap<>();
    private final Map<String, Object> singletonInstances = new ConcurrentHashMap<>();

    /**
     * 获取或创建单例 Bean
     */
    public Object getSingleton(String beanName) {
        if (beanName == null) {
            return null;
        }
        synchronized (this) {
            Object instance = singletonInstances.get(beanName);
            if (instance != null) {
                return instance;
            }
            BeanDefinition definition = singletonBeans.get(beanName);
            if (definition != null) {
                instance = definition.getBean();
                if (instance != null) {
                    singletonInstances.put(beanName, instance);
                    singletonBeans.put(beanName, new SingletonBeanDefinition(definition, instance));
                }
            }
            return instance;
        }
    }

    /**
     * 注册单例 Bean
     */
    public void registerSingleton(String beanName, Object bean) {
        if (beanName == null || bean == null) {
            return;
        }
        synchronized (this) {
            singletonInstances.put(beanName, bean);
        }
    }

    /**
     * 注册单例 BeanDefinition
     */
    public void registerSingleton(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return;
        }
        String beanName = beanDefinition.getName();
        if (beanName == null) {
            return;
        }
        singletonBeans.put(beanName, beanDefinition);
        log.debug("注册单例 BeanDefinition: {}", beanName);
    }

    /**
     * 获取单例 BeanDefinition
     */
    public BeanDefinition getSingletonBeanDefinition(String beanName) {
        return beanName != null ? singletonBeans.get(beanName) : null;
    }

    /**
     * 是否包含单例 Bean
     */
    public boolean containsSingleton(String beanName) {
        return beanName != null && singletonInstances.containsKey(beanName);
    }

    /**
     * 销毁所有单例
     */
    public void destroySingletons() {
        for (BeanDefinition def : singletonBeans.values()) {
            if (def != null) {
                try {
                    def.destroyBean();
                } catch (Exception e) {
                    log.warn("销毁单例 Bean 失败: {}", def.getName(), e);
                }
            }
        }
        singletonBeans.clear();
        singletonInstances.clear();
    }
}