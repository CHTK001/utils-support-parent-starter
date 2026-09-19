package com.chua.common.support.proxy.intercept;

import com.chua.common.support.proxy.ProxyMethod;
import com.chua.common.support.proxy.annotation.Around;
import com.chua.common.support.spi.annotations.Spi;

/**
 * 方法环绕拦截器接口，用于在方法调用前后执行自定义逻辑。
 *
 * <p>本接口实现<b>经典 AOP 环绕通知</b>：
 * 通过 {@link #invoke(ProxyMethod, MethodInvocation)} 在方法调用前后插入自定义逻辑，
 * 调用 {@link MethodInvocation#proceed()} 决定是否放行以及何时放行到目标方法。</p>
 *
 * <p><b>与 {@link com.chua.common.support.proxy.annotation.MethodAnnotationIntercept} 的分工：</b></p>
 * <ul>
 *   <li>{@code MethodAnnotationIntercept} — 按<b>方法上的注解类型</b>触发（如 {@code @Cacheable}、{@code @Async}）</li>
 *   <li>{@code MethodArroundIntercept} — 按<b>方法签名/方法名</b>触发（如所有 {@code save*} 方法、所有 {@code *Service} 方法），
 *       由实现类上的 {@link Around @Around} 注解配置匹配规则。适用于不依赖业务注解的横切能力，如统一日志、全局耗时统计。</li>
 * </ul>
 *
 * <p><b>SPI 注册约定：</b></p>
 * <p>实现类应使用 {@link Spi @Spi} 注解标记并给定一个 SPI 名（任意便于定位的字符串），
 * 以便代理从任意包都能加载到。{@code @Around} 仅描述匹配规则，不承担发现功能。</p>
 *
 * <p><b>执行顺序：</b></p>
 * <p>同一目标方法上匹配到的多个 {@code MethodArroundIntercept} 会按 {@code order} 升序组成洋葱链，
 * 数值越小越靠外层执行。{@code @Around} 上若显式声明 {@code order} 会与 {@link #order()}
 * 取<b>较小值</b>生效（即更靠外的优先），二者都未指定则使用默认值 1000。</p>
 *
 * @author CH
 * @since 2025/11/26
 * @版本 1.1.0
 * @see MethodInvocation
 * @see ProxyMethod
 * @see Around
 * @see com.chua.common.support.proxy.annotation.MethodAnnotationIntercept
 */
public interface MethodArroundIntercept {

    /**
     * 拦截方法调用并执行自定义逻辑（Around 模式）。
     *
     * <p>实现应调用 {@link MethodInvocation#proceed()} 执行目标方法（或下一层拦截器），
     * 不调用即短路返回。可以在 {@code proceed()} 前后插入自定义逻辑，
     * 也可以包装/替换/捕获 proceed() 抛出的异常。</p>
     *
     * @param proxyMethod 代理方法信息，包含目标对象、方法、参数、对象上下文等
     * @param invocation 方法调用链，用于继续执行下一层 拦截器 或目标方法
     * @return 方法执行结果
     * @throws Throwable 如果执行过程中发生异常
     */
    Object invoke(ProxyMethod proxyMethod, MethodInvocation invocation) throws Throwable;

    /**
     * 获取拦截器的执行顺序，数值越小优先级越高（越靠外层执行）。
     *
     * <p>默认值为 1000。当 {@link Around @Around} 上也声明了 {@code order} 时，
     * 框架会取二者较小值（更靠外的生效）。</p>
     *
     * @return 执行顺序值
     */
    default int order() {
        return 1000;
    }
}
