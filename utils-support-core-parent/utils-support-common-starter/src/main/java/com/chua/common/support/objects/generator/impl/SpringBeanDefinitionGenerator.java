package com.chua.common.support.objects.generator.impl;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.TypeBeanDefinition;
import com.chua.common.support.objects.generator.BeanDefinitionGenerator;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Spring Bean 定义生成器，处理 Spring 注解（@组件、@服务、@仓库、@控制器 等）的类。
 *
 * <p>通过反射按类名字符串检测 Spring 注解，不依赖 Spring 编译时注解 API，
 * 实现与 Spring 框架的解耦。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("spring")
@SpiDescribe("Spring Bean 定义生成器")
public class SpringBeanDefinitionGenerator implements BeanDefinitionGenerator {

    /**
     * Spring_注解
    */
    private static final Set<String> SPRING_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.stereotype.RestController",
            "org.springframework.context.annotation.Configuration",
            "org.springframework.web.bind.annotation.RestControllerAdvice",
            "org.springframework.web.bind.annotation.ControllerAdvice"
    );

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 20;
    }

    @Override
    /**
     * 是否支持
    */
    public Boolean isSupport(Class<?> beanClass) {
        if (beanClass == null || beanClass.isInterface() || beanClass.isEnum()
                || beanClass.isAnnotation() || Modifier.isAbstract(beanClass.getModifiers())) {
            return false;
        }
        for (Annotation annotation : beanClass.getAnnotations()) {
            if (SPRING_ANNOTATIONS.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    /**
     * Generate
    */
    public List<BeanDefinition> generate(Class<?> beanClass) {
        List<BeanDefinition> definitions = new ArrayList<>();
        if (beanClass == null) {
            return definitions;
        }
        TypeBeanDefinition definition = TypeBeanDefinition.of(beanClass);
        if (definition != null) {
            definitions.add(definition);
        }
        return definitions;
    }
}
