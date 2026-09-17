package com.chua.common.support.objects.inject;

import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Bean 方法注入器 SPI。
 *
 * <p>负责将容器中的依赖注入到 Bean 的 setter 方法中。
 * 通过 SPI 机制支持多种注入策略（如 @autoinject、@Autowired、@Resource、@Inject 等）。</p>
 *
 * <p>注入流程：
 * <ol>
 *   <li>调用 {@link #isSupport(Method, BeanDefinition)} 判断是否支持该方法</li>
 *   <li>如果支持，调用 {@link #inject(Method, Object, BeanDefinition, Function, Function)} 解析参数并执行</li>
 * </ol></p>
 *
 * @author CH
 * @since 2024/12/20
*/
@Spi
public interface BeanDefinitionMethodInjector {

    /**
    * 是否支持注入该方法。
    *
    * @param method          目标方法
    * @param beanDefinition  Bean 定义
    * @return true 表示支持注入
    */
    boolean isSupport(Method method, BeanDefinition beanDefinition);

    /**
    * 执行方法注入：解析参数并调用方法。
    *
    * @param method          目标方法
    * @param instance        Bean 实例
    * @param beanDefinition  Bean 定义
    * @param beanProvider    按名称查找 Bean 的函数
    * @param typeProvider    按类型查找 Bean 的函数
    */
    void inject(Method method, Object instance, BeanDefinition beanDefinition,
                Function<String, Object> beanProvider,
                Function<Class<?>, Object> typeProvider);
}
