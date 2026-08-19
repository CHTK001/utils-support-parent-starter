package com.chua.common.support.objects.register.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanSingletonRegistry;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 注解 Bean 定义注册器，按注解对 BeanDefinition 进行索引和注册。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("annotation")
@SpiDescribe("注解 Bean 定义注册器")
public class AnnotationBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister {

    /** beanDefinitions */
    private final Map<String, BeanDefinition> beanDefinitions = new ConcurrentSkipListMap<>();
    /** closed */
    private volatile boolean closed;

    @Override
    /** 获取Name */
    public String getName() {
        return "annotation";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 10;
    }

    @Override
    /** 是否Support */
    public boolean isSupport(BeanDefinition beanDefinition) {
        return true;
    }

    @Override
    /** 注册 */
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
        log.debug("注解注册器注册 BeanDefinition: {}", name);
        return true;
    }

    @Override
    /** 注销 */
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

    @Override
    /** 注销 */
    public boolean unregister(String beanName) {
        if (beanName == null || closed) {
            return false;
        }
        beanDefinitions.remove(beanName);
        return true;
    }

    @Override
    /** 获取BeanDefinition */
    public BeanDefinition getBeanDefinition(String beanName) {
        return beanName != null && !closed ? beanDefinitions.get(beanName) : null;
    }

    @Override
    /** 获取BeanDefinitionOfType */
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        List<BeanDefinition> result = new ArrayList<>();
        for (BeanDefinition def : beanDefinitions.values()) {
            if (def == null) {
                continue;
            }
            if (def.getType() != null && (def.getType().equals(typeName) || def.isAssignableFrom(typeName))) {
                result.add(def);
            }
        }
        return result;
    }

    @Override
    /** 获取BeanDefinitionOfType */
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

    @Override
    /** ContainsBean */
    public boolean containsBean(String beanName) {
        return beanName != null && !closed && beanDefinitions.containsKey(beanName);
    }

    @Override
    /** 获取BeanDefinitionNames */
    public Collection<String> getBeanDefinitionNames() {
        return closed ? Collections.emptyList() : new ArrayList<>(beanDefinitions.keySet());
    }

    @Override
    /** 获取BeansWithAnnotation */
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

    @Override
    /** 获取BeansWithMethodAnnotation */
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

    @Override
    /** 初始化 */
    public void initialize() {
        closed = false;
    }

    @Override
    /** 关闭 */
    public void close() {
        closed = true;
        destroySingletons();
        beanDefinitions.clear();
    }

    @Override
    /** 是否Closed */
    public boolean isClosed() {
        return closed;
    }
}
