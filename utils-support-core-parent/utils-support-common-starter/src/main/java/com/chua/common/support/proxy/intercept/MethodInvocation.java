package com.chua.common.support.proxy.intercept;



/**
* 方法调用链接口，用于在环绕拦截器中继续执行目标方法。
*
* <p>通过 {@link #proceed()} 方法可以继续执行下一个拦截器或目标方法。</p>
*
* @author CH
* @since 2025/11/26
* @see MethodArroundIntercept
 */
@FunctionalInterface
public interface MethodInvocation {

    /**
    * 继续执行目标方法或下一个拦截器
    *
    * @return 方法执行结果
    * @throws Throwable 如果执行过程中发生异常
     */
    Object proceed() throws Throwable;
}
