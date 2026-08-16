package com.chua.quarkus.support.objects.register.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.FrameworkBeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanSingletonRegistry;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Quarkus (CDI) Bean 定义注册器（只读）。
 *
 * <p>委托 Jakarta CDI {@code jakarta.enterprise.inject.spi.CDI} 查询 Bean 信息，
 * 所有查询直接委派 CDI 容器。Bean 实例由 CDI 容器管理，本注册器仅做桥接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("quarkus")
@SpiDescribe("Quarkus CDI Bean 定义注册器（只读，委托 CDI 容器）")
public class QuarkusBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister {

    private static final String CDI_CLASS = "jakarta.enterprise.inject.spi.CDI";

    private volatile boolean closed;

    @Override
    public String getName() {
        return "quarkus";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean isSupport(BeanDefinition beanDefinition) {
        return false;
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public boolean register(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("Quarkus CDI Bean 定义注册器不支持手动注册");
    }

    @Override
    public boolean unregister(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("Quarkus CDI Bean 定义注册器不支持手动注销");
    }

    @Override
    public boolean unregister(String beanName) {
        throw new UnsupportedOperationException("Quarkus CDI Bean 定义注册器不支持手动注销");
    }

    @Override
    public void initialize() {
        closed = false;
    }

    @Override
    public BeanDefinition getBeanDefinition(String beanName) {
        if (beanName == null || closed || !ClassUtils.isPresent(CDI_CLASS)) {
            return null;
        }
        try {
            jakarta.enterprise.inject.spi.CDI<Object> cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi == null) {
                return null;
            }
            for (jakarta.enterprise.inject.spi.Bean<?> bean : cdi.getBeanManager().getBeans(Object.class)) {
                if (beanName.equals(bean.getName())) {
                    Class<?> beanClass = bean.getBeanClass();
                    Object instance = cdi.select(beanClass).get();
                    if (instance != null) {
                        return new FrameworkBeanDefinition(beanName, beanClass, instance);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("获取 CDI Bean 失败: {}", beanName, e);
            return null;
        }
    }

    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        try {
            Class<?> type = Class.forName(typeName);
            if (!ClassUtils.isPresent(CDI_CLASS)) {
                return Collections.emptyList();
            }
            jakarta.enterprise.inject.spi.CDI<Object> cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi == null) {
                return Collections.emptyList();
            }
            Object instance = cdi.select(type).get();
            if (instance == null) {
                return Collections.emptyList();
            }
            return List.of(new FrameworkBeanDefinition(type.getName(), type, instance));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String name, String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        if (name != null) {
            BeanDefinition def = getBeanDefinition(name);
            if (def != null && typeName.equals(def.getType())) {
                return List.of(def);
            }
            return Collections.emptyList();
        }
        return getBeanDefinitionOfType(typeName);
    }

    @Override
    public boolean containsBean(String beanName) {
        if (beanName == null || closed) {
            return false;
        }
        return getBeanDefinition(beanName) != null;
    }

    @Override
    public Collection<String> getBeanDefinitionNames() {
        if (closed || !ClassUtils.isPresent(CDI_CLASS)) {
            return Collections.emptyList();
        }
        try {
            jakarta.enterprise.inject.spi.CDI<Object> cdi = jakarta.enterprise.inject.spi.CDI.current();
            if (cdi == null) {
                return Collections.emptyList();
            }
            Set<String> names = new LinkedHashSet<>();
            for (jakarta.enterprise.inject.spi.Bean<?> bean : cdi.getBeanManager().getBeans(Object.class)) {
                String name = bean.getName();
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return Collections.emptyMap();
    }

    @Override
    public Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType) {
        return Collections.emptyMap();
    }

    @Override
    public void close() {
        closed = true;
        destroySingletons();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
