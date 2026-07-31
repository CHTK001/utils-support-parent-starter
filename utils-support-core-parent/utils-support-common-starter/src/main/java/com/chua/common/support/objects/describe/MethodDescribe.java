package com.chua.common.support.objects.describe;

import com.chua.common.support.utils.ClassUtils;
import lombok.Getter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 方法描述，封装对方法的反射调用。
 *
 * @author CH
 * @since 2024/12/20
 */
@Getter
public class MethodDescribe {

    /**
     * 目标
     */
    private final Object target;
    /**
     * 方法名
     */
    private final Method method;

    public MethodDescribe(Object target, Method method) {
        this.target = target;
        this.method = method;
    }

    /** 调用方法 */
    public Object invoke(Object... args) throws InvocationTargetException, IllegalAccessException {
        if (method == null) { return null; }
        ClassUtils.setAccessible(method);
        return method.invoke(target, args);
    }

    /** 方法名 */
    public String getName() {
        return method != null ? method.getName() : null;
    }

    /** 参数数量 */
    public int getParameterCount() {
        return method != null ? method.getParameterCount() : 0;
    }

    /** 返回类型 */
    public Class<?> getReturnType() {
        return method != null ? method.getReturnType() : null;
    }
}