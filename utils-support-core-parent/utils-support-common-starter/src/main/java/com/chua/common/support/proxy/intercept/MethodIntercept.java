package com.chua.common.support.proxy.intercept;

import com.chua.common.support.utils.ClassUtils;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 方法拦截器接口，定义了代理方法调用的拦截机制。
 *
 * <p>该接口提供了方法调用的完整拦截链，包括前置处理、方法调用、后置处理和异常处理。
 * 实现此接口可以自定义代理方法的行为。</p>
 *
 * @param <T> 代理接口类型
 * @author CH
 * @since 2025/7/20
 */
public interface MethodIntercept<T> {

    /**
      * 判断方法是否是 转为字符串 方法
     * @param method 方法
     * @return 是否转为字符串的结果
     */
    static boolean isToString(Method method) {
        return "toString".equals(method.getName()) && method.getParameterCount() == 0;
    }

    /**
      * 判断方法是否是 获取类 方法
     * @param method 方法
     * @return 是否获取类的结果
     */
    static boolean isGetClass(Method method) {
        return "getClass".equals(method.getName()) && method.getParameterCount() == 0;
    }

    /**
      * 判断方法是否是 哈希编码 方法
     * @param method 方法
     * @return 是否哈希编码的结果
     */
    static boolean isHashCode(Method method) {
        return "hashCode".equals(method.getName()) && method.getParameterCount() == 0;
    }

    /**
     * 判断方法是否是 equals 方法
     * @param method 方法
     * @return 是否equals的结果
     */
    static boolean isEquals(Method method) {
        return "equals".equals(method.getName()) && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == Object.class;
    }

    /**
     * 方法调用前的前置处理
     */
    default void before(Object obj, Method method, Object[] args, T proxy) {
    }

    /**
     * 拦截方法调用并执行自定义逻辑
     */
    Object invoke(Object obj, Method method, Object[] args, T proxy) throws Throwable;

    /**
     * 方法调用后的后置处理
     */
    default void after(Object obj, Method method, Object[] args, T proxy) {
    }

    /**
     * 异常处理，当方法调用发生异常时此方法会被调用
     *
     * @return 异常处理结果，返回 空 表示不处理异常
     */
    default Object handleException(Object obj, Method method, Object[] args, T proxy, Throwable throwable) {
        return null;
    }

    /**
     * 调用目标对象的原始方法
     */
    default Object defaultInvoke(Object obj, Method method, Object[] args, T proxy) throws Throwable {
        return ClassUtils.invokeMethod(method, obj, args);
    }
}
