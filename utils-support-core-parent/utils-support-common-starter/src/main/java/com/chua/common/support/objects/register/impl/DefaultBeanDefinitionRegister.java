package com.chua.common.support.objects.register.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanSingletonRegistry;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 默认 Bean 定义注册器，基于内存存储 Beandefinition。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("default")
@SpiDescribe("默认 Bean 定义注册器")
public class DefaultBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister {

    /**
     * Beandefinitions
     */
    private final Map<String, BeanDefinition> beanDefinitions = new ConcurrentSkipListMap<>();
    /**
     * closed
     */
    private volatile boolean closed;

    /**
     * 获取名称
     */
    @Override
    public String getName() {
        return "default";
    }

    /**
     * 获取Priority
     */
    @Override
    public int getPriority() {
        return 0;
    }

    /**
     * 是否支持
     */
    @Override
    public boolean isSupport(BeanDefinition beanDefinition) {
        return true;
    }

    /**
     * 注册
     */
    @Override
    public boolean register(BeanDefinition beanDefinition) {
        if (beanDefinition == null || closed) {
            return false;
        }
        String name = beanDefinition.getName();
        if (name == null) {
            return false;
        }
        beanDefinitions.put(name, beanDefinition);
        registerSingleton(beanDefinition);
        log.debug("注册 BeanDefinition: {}", name);
        return true;
    }

    /**
     * 注销
     */
    @Override
    public boolean unregister(BeanDefinition beanDefinition) {
        if (beanDefinition == null || closed) {
            return false;
        }
        String name = beanDefinition.getName();
        if (name == null) {
            return false;
        }
        beanDefinitions.remove(name);
        return true;
    }

    /**
     * 注销
     */
    @Override
    public boolean unregister(String beanName) {
        if (beanName == null || closed) {
            return false;
        }
        beanDefinitions.remove(beanName);
        return true;
    }

    /**
     * 获取Beandefinition
     */
    @Override
    public BeanDefinition getBeanDefinition(String beanName) {
        if (beanName == null || closed) {
            return null;
        }
        return beanDefinitions.get(beanName);
    }

    /**
     * 获取Beandefinition的类型
     */
    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        List<BeanDefinition> result = new ArrayList<>();
        for (BeanDefinition def : beanDefinitions.values()) {
            if (def == null) {
                continue;
            }
            // 同时兼容按当前类型与父类/接口匹配（类型层次缓存）
            if (def.getType() != null && (def.getType().equals(typeName) || def.isAssignableFrom(typeName))) {
                result.add(def);
            }
        }
        return result;
    }

    /**
     * 获取Beandefinition的类型
     */
    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String name, String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        if (name != null) {
            BeanDefinition def = beanDefinitions.get(name);
            if (def != null && def.getType() != null
                    && (def.getType().equals(typeName) || def.isAssignableFrom(typeName))) {
                return List.of(def);
            }
            return Collections.emptyList();
        }
        return getBeanDefinitionOfType(typeName);
    }

    /**
     * containsBean
     */
    @Override
    public boolean containsBean(String beanName) {
        return beanName != null && !closed && beanDefinitions.containsKey(beanName);
    }

    /**
     * 获取Beandefinition名称
     */
    @Override
    public Collection<String> getBeanDefinitionNames() {
        return closed ? Collections.emptyList() : new ArrayList<>(beanDefinitions.keySet());
    }

    /**
     * 获取Beanwith注解
     */
    @Override
    public Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null || closed) {
            return Collections.emptyMap();
        }
        Map<String, BeanDefinition> result = new LinkedHashMap<>();
        for (BeanDefinition def : beanDefinitions.values()) {
            if (def == null) {
                continue;
            }
            if (def.isAnnotationPresent(annotationType)) {
                String name = def.getName();
                if (name != null) {
                    result.put(name, def);
                }
            }
        }
        return result;
    }

    /**
     * 获取Beanwith方法注解
     */
    @Override
    public Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null || closed) {
            return Collections.emptyMap();
        }
        Map<String, BeanDefinition> result = new LinkedHashMap<>();
        for (BeanDefinition def : beanDefinitions.values()) {
            if (def == null) {
                continue;
            }
            if (def.hasMethodWithAnnotation(annotationType)) {
                String name = def.getName();
                if (name != null) {
                    result.put(name, def);
                }
            }
        }
        return result;
    }

    /**
     * 初始化
     */
    @Override
    public void initialize() {
        // 内存注册器无需特殊初始化
        closed = false;
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        closed = true;
        destroySingletons();
        beanDefinitions.clear();
    }

    /**
     * 是否Closed
     */
    @Override
    public boolean isClosed() {
        return closed;
    }
}
