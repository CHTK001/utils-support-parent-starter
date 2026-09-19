package com.chua.common.support.objects.generator.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.objects.annotation.AutoService;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.TypeBeanDefinition;
import com.chua.common.support.objects.generator.BeanDefinitionGenerator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * auto服务 Bean 定义生成器，处理 @auto服务 注解的类。
 *
 * @author CH
 * @since 2024/12/20
 */
@Slf4j
@Spi("autoservice")
@SpiDescribe("AutoService Bean 定义生成器")
public class AutoServiceBeanDefinitionGenerator implements BeanDefinitionGenerator {

    @Override
    /**
     * 获取Priority
    */
    public int getPriority() {
        return 30;
    }

    @Override
    /**
     * 是否支持
    */
    public Boolean isSupport(Class<?> beanClass) {
        if (beanClass == null) {
            return false;
        }
        if (beanClass.isInterface() || beanClass.isEnum() || beanClass.isAnnotation()
                || java.lang.reflect.Modifier.isAbstract(beanClass.getModifiers())) {
            return false;
        }
        return beanClass.isAnnotationPresent(AutoService.class);
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
