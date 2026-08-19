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
 * Quarkus Bean 定义生成器，处理 Quarkus/CDI 注解（@ApplicationScoped、@Singleton 等）的类。
 *
 * <p>通过反射按类名字符串检测 Quarkus/CDI 注解，不依赖编译时注解 API。
 * 同时支持 javax 和 jakarta 命名空间。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("quarkus")
@SpiDescribe("Quarkus/CDI Bean 定义生成器")
public class QuarkusBeanDefinitionGenerator implements BeanDefinitionGenerator {

    /** Cdi_annotations */
    private static final Set<String> CDI_ANNOTATIONS = Set.of(
            "javax.enterprise.context.ApplicationScoped",
            "javax.enterprise.context.RequestScoped",
            "javax.enterprise.context.SessionScoped",
            "javax.enterprise.context.Dependent",
            "javax.enterprise.context.ConversationScoped",
            "javax.inject.Singleton",
            "javax.inject.Named",
            "jakarta.enterprise.context.ApplicationScoped",
            "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped",
            "jakarta.enterprise.context.Dependent",
            "jakarta.enterprise.context.ConversationScoped",
            "jakarta.inject.Singleton",
            "jakarta.inject.Named"
    );

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 20;
    }

    @Override
    /** 是否Support */
    public Boolean isSupport(Class<?> beanClass) {
        if (beanClass == null || beanClass.isInterface() || beanClass.isEnum()
                || beanClass.isAnnotation() || Modifier.isAbstract(beanClass.getModifiers())) {
            return false;
        }
        for (Annotation annotation : beanClass.getAnnotations()) {
            if (CDI_ANNOTATIONS.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    /** Generate */
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
