package com.chua.common.support.objects.register;

import com.chua.common.support.objects.definition.BeanDefinition;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
   * Bean 定义注册器 SPI，负责 Beandefinition 的存储和检索。
 *
 * @author CH
 * @since 2024/12/20
 */
public interface BeanDefinitionRegister {

    /**
     * 获取注册器的唯一标识名称。
     *
     * @return 注册器名称字符串
     */
    String getName();

    /**
     * 获取注册器的优先级，数值越小优先级越高。
     *
     * @return 优先级整数值
     */
    int getPriority();

    /**
      * 判断当前注册器是否支持处理指定的 Beandefinition。
     *
     * @param beanDefinition 待检查的 Bean 定义对象
     * @return 如果支持则返回 true，否则返回 false
     */
    boolean isSupport(BeanDefinition beanDefinition);

    /**
      * 将指定的 Beandefinition 注册到当前注册器中。
     *
     * @param beanDefinition 需要注册的 Bean 定义对象
     * @return 注册成功返回 true，失败返回 false
     */
    boolean register(BeanDefinition beanDefinition);

    /**
      * 从注册器中注销指定的 Beandefinition。
     *
     * @param beanDefinition 需要注销的 Bean 定义对象
     * @return 注销成功返回 true，失败返回 false
     */
    boolean unregister(BeanDefinition beanDefinition);

    /**
      * 根据 Bean 名称从注册器中注销对应的 Beandefinition。
     *
     * @param beanName 需要注销的 Bean 名称
     * @return 注销成功返回 true，失败返回 false
     */
    boolean unregister(String beanName);

    /**
      * 根据 Bean 名称获取对应的 Beandefinition。
     *
     * @param beanName 目标 Bean 的名称
     * @return 找到的 Bean 定义对象，未找到则返回 空
     */
    BeanDefinition getBeanDefinition(String beanName);

    /**
      * 根据类型获取所有匹配的 Beandefinition 集合。
     *
     * @param typeName 目标类型的类名或接口名
     * @return 包含匹配 Bean 定义的集合
     */
    Collection<BeanDefinition> getBeanDefinitionOfType(String typeName);

    /**
      * 根据名称和类型组合获取特定的 Beandefinition 集合。
     *
     * @param name     目标 Bean 的名称
     * @param typeName 目标类型的类名或接口名
     * @return 包含匹配 Bean 定义的集合
     */
    Collection<BeanDefinition> getBeanDefinitionOfType(String name, String typeName);

    /**
     * 判断注册器中是否包含指定名称的 Bean。
     *
     * @param beanName 待检查的 Bean 名称
     * @return 如果包含则返回 true，否则返回 false
     */
    boolean containsBean(String beanName);

    /**
     * 获取注册器中所有 Bean 的名称集合。
     *
     * @return 包含所有 Bean 名称的集合
     */
    Collection<String> getBeanDefinitionNames();

    /**
      * 获取注册器中所有的 Beandefinition 对象。
     *
     * @return 包含所有 Bean 定义的集合
     */
    default Collection<BeanDefinition> getAllBeanDefinitions() {
        Collection<String> names = getBeanDefinitionNames();
        Collection<BeanDefinition> definitions = new ArrayList<>();
        for (String name : names) {
            BeanDefinition def = getBeanDefinition(name);
            if (def != null) {
                definitions.add(def);
            }
        }
        return definitions;
    }

    /**
     * 获取所有标注了指定注解的 Bean 定义映射。
     *
     * @param annotationType 目标注解类型
     * @return 键为 Bean 名称，值为 Bean 定义的映射关系
     */
    Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType);

    /**
     * 获取所有方法上标注了指定注解的 Bean 定义映射。
     *
     * @param annotationType 目标注解类型
     * @return 键为 Bean 名称，值为 Bean 定义的映射关系
     */
    Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType);

    /**
     * 初始化注册器所需的内部资源和状态。
     */
    void initialize();

    /**
     * 关闭注册器并释放相关资源。
     */
    void close();

    /**
     * 判断注册器是否已经处于关闭状态。
     *
     * @return 已关闭返回 true，未关闭返回 false
     */
    boolean isClosed();

    /**
     * 判断当前注册器是否支持写入操作（默认支持）。
     *
     * @return 是否可写，默认为 true
     */
    default boolean isWritable() {
        return true;
    }
}