package com.chua.runtime.agent;

import com.chua.runtime.spy.RuntimeSpy;

/**
* 字节码插桩 Bootstrap — 转发到 runtimespy。
*
* <p>由 SpyTransformer 在字节码层调用（{@code INVOKESTATIC com/chua/runtime/agent/Bootstrap.onIntercept}）。
* 该类位于 runtime智能体 模块（javaagent 启动 fat-jar），
* 由<b>系统 classloader</b>加载，对 bootstrap classloader 加载的 JDK 类（Java.net.套接字、
* Java.net.httpurlconnection 等）也可见，
* 避免 {@code NoClassDefFoundError} 问题。</p>
*
* <p>Bootstrap 的 onIntercept / onException 方法签名必须与 RuntimeSpy 中同名方法一致，
* 因为 spy转换 生成的字节码使用固定描述符。</p>
*
* @author CH
* @since 4.0.0.42
 */
public final class Bootstrap {

    /** 创建 Bootstrap 实例 */
    private Bootstrap() {
    }

    /**
    * 拦截入口（ENTRY / EXIT / 日志_PRE / ...）。
    *
    * <p>由 SpyTransformer 注入的字节码调用，签名为
    * {@code static void onIntercept(Object thisRef, String className, String methodName, String descriptor, String point)}，
    * thisref 是受拦截实例（套接字/htturlconnection/文件输入流 等），静态方法或 JDK 内部时为 空。
    * 该方法转发到 {@link RuntimeSpy#onIntercept(String, String, String, String, Object)}，
    * 由其把 thisref 写入 intercept上下文.用户数据 供 处理器 使用。</p>
    *
    * @param thisRef  受拦截实例（可为 空）
    * @param className  目标类内部名
    * @param methodName 方法名
    * @param descriptor  方法描述符
    * @param point       插桩点 键
     */
    public static void onIntercept(Object thisRef, String className, String methodName,
                                   String descriptor, String point) {
        RuntimeSpy.onIntercept(className, methodName, descriptor, point, thisRef);
    }

    /**
    * 异常拦截入口（异常）。
    *
    * @param className  目标类内部名
    * @param methodName 方法名
    * @param throwable  异常对象
     */
    public static void onException(String className, String methodName, Throwable throwable) {
        RuntimeSpy.onException(className, methodName, throwable);
    }
}
