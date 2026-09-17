package com.chua.common.support.proxy.annotation;

import com.chua.common.support.proxy.intercept.MethodInvocation;
import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.spi.annotations.Spi;

import java.lang.annotation.Annotation;

/**
 * 注解方法拦截器接口，用于拦截带有特定注解的方法调用。
 *
 * <p>该接口定义了基于注解的方法拦截机制，允许在目标方法执行前后插入自定义逻辑
 * （即 AOP 中的 around 通知模式），通过 {@link MethodInvocation#proceed()} 决定是否继续执行。
 * 实现类之间通过 {@link #order()} 方法确定执行顺序。</p>
 *
 * <p><b>SPI 注册约定（重要）：</b></p>
 * <p>实现类必须使用 {@link Spi @Spi} 注解标记，并将 {@code value} 设置为<b>被拦截注解的全限定类名</b>。
 * 代理框架在创建代理时会按方法上的注解全名到 SPI 注册表中查找拦截器，匹配到则执行。
 * 同时框架在运行时还会用 {@link #annotationType()} 进行类型安全校验，二者必须保持一致。</p>
 *
 * <p>之所以要求用注解全限定类名作为 SPI 名字，是为了让拦截器实现类可以放置在<b>任意包</b>
 * （而不局限于 {@code MethodAnnotationIntercept} 接口所在包），只要 SPI 注册表能通过名字定位到即可。</p>
 *
 * <p>主要用途：</p>
 * <ul>
 *   <li>方法级别的日志记录</li>
 *   <li>方法级别的权限控制</li>
 *   <li>方法级别的缓存处理（如 {@code @Cacheable}、{@code @CachePut}、{@code @CacheEvict}）</li>
 *   <li>方法级别的事务管理</li>
 *   <li>方法限流、熔断、异步执行等</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 1. 定义业务注解
 * @Target(ElementType.METHOD)
 * @Retention(RetentionPolicy.RUNTIME)
 * public @interface Cacheable {
 *     String value();
 * }
 *
 * // 2. 实现拦截器：@Spi 中的 value 必须等于 Cacheable.class.getName()
 * @Spi("com.example.annotation.Cacheable")
 * public class CacheableIntercept implements MethodAnnotationIntercept<Cacheable> {
 *     @Override
 *     public Class<Cacheable> annotationType() {
 *         return Cacheable.class;
 *     }
 *
 *     @Override
 *     public int order() {
 *         return 100;
 *     }
 *
 *     @Override
 *     public Object intercept(Cacheable annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable {
 *         String key = annotation.value();
 *         Object cached = cache.get(key);
 *         if (cached != null) {
 *             return cached;
 *         }
 *         Object result = invocation.proceed();
 *         cache.put(key, result);
 *         return result;
 *     }
 * }
 * }</pre>果;
 *     }
 * }
 * }</pre>
 *
 * @param <A> 注解类型，必须继承自 {@link java.lang.annotation.Annotation}
 * @author CH
 * @since 2025/11/26
 * @版本 1.1.0
 * @see ProxyMethod
 * @see MethodInvocation
 * @see Spi
*/
public interface MethodAnnotationIntercept<A extends Annotation> {

    /**
    * 获取要拦截的注解类型。
    *
    * <p>必须与 {@link Spi @Spi} 中 {@code value} 所指定的注解全名对应的 {@code Class} 完全一致，
    * 框架会在运行时检查二者一致性，不一致则跳过该拦截器。</p>
    *
    * @return 注解类型 类 对象，不可为 空
    */
    Class<A> annotationType();

    /**
    * 获取拦截器的执行顺序。
    *
    * <p>数值越小，执行优先级越高（外层执行）。默认值为 1000。
    * 当同一注解上挂载多个拦截器时，按 {@code order} 升序组成洋葱调用链。</p>
    *
    * @return 执行顺序值
    */
    default int order() {
        return 1000;
    }

    /**
    * 拦截带有指定注解的方法调用（Around 模式）。
    *
    * <p>实现方可以：</p>
    * <ul>
    *   <li>在 {@code invocation.proceed()} 之前执行前置逻辑（如缓存命中直接返回）</li>
    *   <li>调用 {@code invocation.proceed()} 放行到下一层拦截器或目标方法</li>
    *   <li>在 {@code invocation.proceed()} 之后执行后置逻辑（如缓存写入）</li>
    *   <li>不调用 {@code invocation.proceed()} 即可短路返回，阻止目标方法执行</li>
    * </ul>
    *
    * @param annotation 方法上的注解实例，携带注解的配置参数
    * @param proxyMethod 代理方法信息（包含目标对象、方法、参数、对象上下文等）
    * @param invocation 方法调用链，用于继续执行目标方法
    * @return 方法执行结果
    * @throws Throwable 如果拦截过程中发生异常
    */
    Object intercept(A annotation, ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable;
}
