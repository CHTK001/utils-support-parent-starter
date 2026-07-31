package com.chua.spring.support.objects.register.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.FrameworkBeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanSingletonRegistry;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.spring.support.configuration.SpringBeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Spring Bean 定义注册器（只读）。
 *
 * <p>委托 {@link SpringBeanUtils} 获取 Spring {@link ApplicationContext}，
 * 所有查询直接委派 Spring 上下文。
 * Bean 实例由 Spring 容器管理，本注册器仅做桥接，不执行框架 IoC 生命周期。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("spring")
@SpiDescribe("Spring Bean 定义注册器（只读，委托 Spring ApplicationContext）")
public class SpringBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister {

    private volatile boolean closed;

    @Override
    public String getName() {
        return "spring";
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
        throw new UnsupportedOperationException("Spring Bean 定义注册器不支持手动注册");
    }

    @Override
    public boolean unregister(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("Spring Bean 定义注册器不支持手动注销");
    }

    @Override
    public boolean unregister(String beanName) {
        throw new UnsupportedOperationException("Spring Bean 定义注册器不支持手动注销");
    }

    @Override
    public void initialize() {
        closed = false;
    }

    @Override
    public BeanDefinition getBeanDefinition(String beanName) {
        if (beanName == null || closed) {
            return null;
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        if (ctx == null || !ctx.containsBean(beanName)) {
            return null;
        }
        try {
            Class<?> type = ctx.getType(beanName);
            Object bean = ctx.getBean(beanName);
            if (type == null || bean == null) {
                return null;
            }
            return new FrameworkBeanDefinition(beanName, type, bean);
        } catch (Exception e) {
            log.debug("获取 Spring Bean 失败: {}", beanName, e);
            return null;
        }
    }

    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        if (ctx == null) {
            return Collections.emptyList();
        }
        try {
            Class<?> type = Class.forName(typeName);
            Map<String, ?> beans = ctx.getBeansOfType(type);
            List<BeanDefinition> result = new ArrayList<>(beans.size());
            for (Map.Entry<String, ?> entry : beans.entrySet()) {
                result.add(new FrameworkBeanDefinition(entry.getKey(), type, entry.getValue()));
            }
            return result;
        } catch (ClassNotFoundException e) {
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
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        return ctx != null && ctx.containsBean(beanName);
    }

    @Override
    public Collection<String> getBeanDefinitionNames() {
        if (closed) {
            return Collections.emptyList();
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        if (ctx == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(ctx.getBeanDefinitionNames());
    }

    @Override
    public Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        if (annotationType == null || closed) {
            return Collections.emptyMap();
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        if (ctx == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> beans = ctx.getBeansWithAnnotation(annotationType);
        Map<String, BeanDefinition> result = new LinkedHashMap<>(beans.size());
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            result.put(entry.getKey(),
                    new FrameworkBeanDefinition(entry.getKey(), entry.getValue().getClass(), entry.getValue()));
        }
        return result;
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
