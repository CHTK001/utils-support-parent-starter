package com.chua.spring.support.objects.register.impl;

import com.chua.common.support.reflection.ReflectUtils;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Bean 定义注册器（只读）。
 *
 * <p>委托 {@link SpringBeanUtils} 获取 Spring {@link ApplicationContext}，
 * 所有查询直接委派 Spring 上下文。
 * Bean 实例由 Spring 容器管理，本注册器仅做桥接，不执行框架 IOC 生命周期。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("spring")
@SpiDescribe("Spring Bean 定义注册器（只读，委托 Spring ApplicationContext）")
public class SpringBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister {

    /** closed */
    private volatile boolean closed;

    @Override
    /** 获取名称 */
    public String getName() {
        return "spring";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 100;
    }

    @Override
    /** 是否支持 */
    public boolean isSupport(BeanDefinition beanDefinition) {
        return false;
    }

    @Override
    /** 是否Writable */
    public boolean isWritable() {
        return false;
    }

    @Override
    /** 注册 */
    public boolean register(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("Spring Bean 定义注册器不支持手动注册");
    }

    @Override
    /** 注销 */
    public boolean unregister(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("Spring Bean 定义注册器不支持手动注销");
    }

    @Override
    /** 注销 */
    public boolean unregister(String beanName) {
        throw new UnsupportedOperationException("Spring Bean 定义注册器不支持手动注销");
    }

    @Override
    /** 初始化 */
    public void initialize() {
        closed = false;
    }

    @Override
    /** 获取Beandefinition */
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
            log.debug("[spring-impl] 获取 Spring Bean 失败: {}", beanName, e);
            return null;
        }
    }

    @Override
    /** 获取Beandefinition的类型 */
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        if (ctx == null) {
            return Collections.emptyList();
        }
        Class<?> type = ReflectUtils.forName(typeName);
        if (type == null) {
            return Collections.emptyList();
        }
        Map<String, ?> beans = ctx.getBeansOfType(type);
        if (beans == null || beans.isEmpty()) {
            return Collections.emptyList();
        }
        List<BeanDefinition> result = new ArrayList<>(beans.size());
        for (Map.Entry<String, ?> entry : beans.entrySet()) {
            result.add(new FrameworkBeanDefinition(entry.getKey(), type, entry.getValue()));
        }
        return result;
    }

    @Override
    /** 获取Beandefinition的类型 */
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
    /** containsBean */
    public boolean containsBean(String beanName) {
        if (beanName == null || closed) {
            return false;
        }
        ApplicationContext ctx = SpringBeanUtils.getApplicationContextOrNull();
        return ctx != null && ctx.containsBean(beanName);
    }

    @Override
    /** 获取Beandefinition名称 */
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
    /** 获取Beanwith注解 */
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
    /** 获取Beanwith方法注解 */
    public Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType) {
        return Collections.emptyMap();
    }

    @Override
    /** 关闭 */
    public void close() {
        closed = true;
        destroySingletons();
    }

    @Override
    /** 是否Closed */
    public boolean isClosed() {
        return closed;
    }
}
