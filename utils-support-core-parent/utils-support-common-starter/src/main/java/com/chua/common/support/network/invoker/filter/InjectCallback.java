package com.chua.common.support.network.invoker.filter;

/**
* 注入回调，在每次远程调用时获取要注入的值。
*
* <p>与 {@code @RemoteInject} 注解功能一致，提供编程式注入能力。
* 通过 {@link com.chua.common.support.network.invoker.Invoker#addInject(String, InjectCallback)} 注册。</p>
*
* @author CH
* @since 4.0.0.42
* @see com.chua.common.support.network.invoker.Invoker#addInject(String, InjectCallback)
 */
@FunctionalInterface
public interface InjectCallback {

    /**
    * 获取要注入的值。
    *
    * @param context 当前调用上下文，可访问请求头、返回值、属性等
    * @return 要注入的值，返回 null 则不注入
     */
    String apply(InvocationContext context);
}