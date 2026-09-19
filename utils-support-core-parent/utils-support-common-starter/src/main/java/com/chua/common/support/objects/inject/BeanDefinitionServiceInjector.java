package com.chua.common.support.objects.inject;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.objects.definition.BeanDefinition;

import java.lang.reflect.Field;
import java.util.function.Function;

/**
 * Bean 服务注入器 SPI。
 *
 * <p>负责将容器中的依赖 Bean 注入到目标 Bean 的字段中。
 * 通过 SPI 机制支持多种注入策略（如 @autoinject、@Resource、@Inject 等）。</p>
 *
 * <p>注入流程：
 * <ol>
 *   <li>调用 {@link #isSupport(Field, BeanDefinition)} 判断是否支持该字段</li>
 *   <li>如果支持，调用 {@link #inject(Field, Object, BeanDefinition, Function, Function)} 执行注入</li>
 *   <li>容器负责将返回的注入值通过反射设置到目标字段上</li>
 * </ol></p>
 *
 * <p>多个注入器可以同时生效，只要 {@code isSupport} 返回 {@code true} 的注入器
 * 都会被执行。容器会按优先级顺序调用注入器，优先级由 {@code @Spi} 注解的
 * {@code order} 属性控制。</p>
 *
 * <p>实现类应当处理以下边界情况：
 * <ul>
 *   <li>field 为 null — 应返回 false 或 null</li>
 *   <li>bean 为 null — 应返回 null（无法注入到 null 目标）</li>
 *   <li>beanProvider 为 null — 按名称查找不可用，应尝试按类型查找</li>
 *   <li>typeProvider 为 null — 按类型查找不可用，仅能按名称查找</li>
 *   <li>找不到匹配的 Bean — 根据注解的 required 属性决定是抛异常还是返回 null</li>
 * </ul></p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi
public interface BeanDefinitionServiceInjector {

    /**
     * 是否支持注入该字段。
     *
     * <p>根据字段的注解类型、类型特征等判断是否能够处理该字段的注入。
     * 例如：
     * <ul>
     *   <li>字段标注了 @AutoInject → 返回 true</li>
     *   <li>字段标注了 @Resource → 返回 true</li>
     *   <li>其他情况 → 返回 false</li>
     * </ul></p>
     *
     * <p>该方法应当轻量且无副作用，因为容器会对每个 Bean 的每个字段
     * 调用此方法进行判断。</p>
     *
     * @param field          目标字段（可能为 空）
     * @param beanDefinition Bean 定义（提供上下文信息）
     * @return true 表示支持注入该字段
     */
    boolean isSupport(Field field, BeanDefinition beanDefinition);

    /**
     * 执行注入。
     *
     * <p>从容器中查找匹配的 Bean 并返回。容器负责将返回值
     * 通过反射设置到目标字段上。注入器本身不直接操作字段，仅返回注入值。</p>
     *
     * <p>查找策略：
     * <ul>
     *   <li>如果注解指定了 Bean 名称，优先使用 {@code beanProvider} 按名称查找</li>
     *   <li>如果未指定名称，使用 {@code typeProvider} 按字段类型查找</li>
     *   <li>如果两个 Provider 都返回 null，根据注解的 required 属性处理</li>
     * </ul></p>
     *
     * @param field          目标字段
     * @param bean           目标 Bean 实例（用于日志和异常信息）
     * @param beanDefinition Bean 定义（提供上下文信息）
     * @param beanProvider   按名称查找 Bean 的函数（可能为 空）
     * @param typeProvider   按类型查找 Bean 的函数（可能为 空）
     * @return 注入的值，null 表示未找到匹配的 Bean（且非必须注入）
     * @throws IllegalStateException 如果必须注入但找不到匹配的 Bean
     */
    Object inject(Field field, Object bean, BeanDefinition beanDefinition,
                  Function<String, Object> beanProvider,
                  Function<Class<?>, Object> typeProvider);
}
