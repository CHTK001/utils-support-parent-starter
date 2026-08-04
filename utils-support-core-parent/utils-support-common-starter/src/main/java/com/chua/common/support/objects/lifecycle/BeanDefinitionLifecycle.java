package com.chua.common.support.objects.lifecycle;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.BeanScope;
import org.jspecify.annotations.NullUnmarked;

/**
 * Bean 生命周期处理器 SPI，用于处理 Bean 的初始化和销毁过程。
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
@Spi
public interface BeanDefinitionLifecycle {

    /**
     * 判断当前处理器是否支持指定的 Bean 定义。
     *
     * @param beanDefinition 需要检查的 Bean 定义对象
     * @return 如果支持则返回 true，否则返回 false
     */
    boolean isSupport(BeanDefinition beanDefinition);

    /**
     * 执行 Bean 的初始化逻辑。
     *
     * @param beanDefinition 对应的 Bean 定义对象
     * @param bean           待初始化的 Bean 实例
     * @throws Exception 如果初始化过程中发生错误，抛出异常
     */
    void init(BeanDefinition beanDefinition, Object bean) throws Exception;

    /**
     * 执行 Bean 的销毁逻辑。
     *
     * @param beanDefinition 对应的 Bean 定义对象
     * @param bean           待销毁的 Bean 实例
     * @throws Exception 如果销毁过程中发生错误，抛出异常
     */
    void destroy(BeanDefinition beanDefinition, Object bean) throws Exception;

    /**
     * 判断指定的 Bean 定义是否为单例模式。
     *
     * @param beanDefinition 需要检查的 Bean 定义对象
     * @return 如果是单例或 scope 为 null 则返回 true，否则返回 false
     */
    default boolean isSingleton(BeanDefinition beanDefinition) {
        if (beanDefinition == null) {
            return false;
        }
        BeanScope scope = beanDefinition.getScope();
        if (scope == null || BeanScope.SINGLETON == scope) {
            return true;
        }
        return false;
    }
}