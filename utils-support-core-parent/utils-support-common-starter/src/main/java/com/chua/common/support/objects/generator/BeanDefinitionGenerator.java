package com.chua.common.support.objects.generator;

import com.chua.common.support.objects.definition.BeanDefinition;

import java.util.List;

/**
* Bean 定义生成器 SPI，用于从指定的 类 对象生成对应的 Beandefinition 列表。
* <p>
* 该接口定义了生成 Bean 定义的标准流程，支持通过优先级排序不同实现类。
*
* @author CH
* @since 2024/12/20
 */
public interface BeanDefinitionGenerator {

    /**
    * 获取当前生成器的优先级。
    * <p>
    * 值越大表示优先级越高，在多个生成器共存时优先执行高优先级的生成器。
    *
    * @return 返回优先级整数值，默认为 0
     */
    default int getPriority() {
        return 0;
    }

    /**
    * 判断当前生成器是否支持处理指定的 Bean 类。
    * <p>
    * 如果返回 true，则调用 generate 方法；否则跳过该生成器。
    *
    * @param beanClass 需要处理的 Bean 类对象
    * @return 如果支持处理则返回 true，否则返回 false
     */
    Boolean isSupport(Class<?> beanClass);

    /**
    * 根据指定的 Bean 类生成对应的 Beandefinition 列表。
    * <p>
    * 该方法仅在 是否支持 返回 true 时被调用，负责构建完整的 Bean 定义集合。
    *
    * @param beanClass 需要生成定义的 Bean 类对象
    * @return 返回生成的 Beandefinition 列表，可能为空但不应为 空
     */
    List<BeanDefinition> generate(Class<?> beanClass);
}