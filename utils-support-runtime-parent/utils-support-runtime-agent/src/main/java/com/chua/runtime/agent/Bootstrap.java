package com.chua.runtime.agent;

import com.chua.runtime.spy.RuntimeSpy;

/**
 * 字节码插桩 Bootstrap — 转发到 RuntimeSpy。
 *
 * <p>由 SpyTransformer 在字节码层调用（{@code INVOKESTATIC com/chua/runtime/agent/Bootstrap.onIntercept}）。
 * 该类位于 RuntimeAgent 模块（javaagent 启动 fat-jar），
 * 由<b>系统 classloader</b>加载，对 bootstrap classloader 加载的 JDK 类（java.net.Socket、
 * java.net.HttpURLConnection 等）也可见，
 * 避免 {@code NoClassDefFoundError} 问题。</p>
 *
 * <p>Bootstrap 的 onIntercept / onException 方法签名必须与 RuntimeSpy 中同名方法一致，
 * 因为 SpyTransformer 生成的字节码使用固定描述符。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /**
     * 拦截入口（ENTRY / EXIT）。
     *
     * @param className  目标类内部名
     * @param methodName 方法名
     * @param descriptor  方法描述符
     * @param point       插桩点 key
     */
    public static void onIntercept(String className, String methodName, String descriptor, String point) {
        RuntimeSpy.onIntercept(className, methodName, descriptor, point);
    }

    /**
     * 异常拦截入口（EXCEPTION）。
     *
     * @param className  目标类内部名
     * @param methodName 方法名
     * @param throwable  异常对象
     */
    public static void onException(String className, String methodName, Throwable throwable) {
        RuntimeSpy.onException(className, methodName, throwable);
    }
}
