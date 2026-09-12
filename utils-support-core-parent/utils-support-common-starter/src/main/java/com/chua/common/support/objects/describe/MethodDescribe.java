package com.chua.common.support.objects.describe;

import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Method;

/**
 * 方法描述元数据。
 *
 * <p>封装 Java {@link Method} 的基本信息（方法名、参数类型、返回类型等），
 * 供路由映射等场景使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MethodDescribe {

    /** 目标 */
    private final Object target;
    /** 方法 */
    private final Method method;
    /** 名称 */
    private final String name;
    private final Class<?> returnType; // 返回类型
    private final Class<?>[] parameterTypes; // 参数类型
    /** Parameternames */
    private final String[] parameterNames;

    /**
     * 构造方法描述（仅方法元数据）。
     *
     * @param method Java 反射方法对象
     */
    public MethodDescribe(Method method) {
        this(null, method);
    }

    /**
     * 构造方法描述（含目标对象）。
     *
     * @param target 目标对象实例
     * @param method Java 反射方法对象
     */
    public MethodDescribe(Object target, Method method) {
        this.target = target;
        this.method = method;
        this.name = method.getName();
        this.returnType = method.getReturnType();
        this.parameterTypes = method.getParameterTypes();
 // 不解析参数名（需要 -参数 编译参数）
        this.parameterNames = new String[0];
    }

    /**
     * @return 方法名
     */
    public String getName() {
        return name;
    }

    /**
     * @return 返回类型
     */
    public Class<?> getReturnType() {
        return returnType;
    }

    /**
     * @return 参数类型数组
     */
    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    /**
     * @return 参数名数组
     */
    public String[] getParameterNames() {
        return parameterNames;
    }

    /**
     * 调用方法（使用构造时提供的目标对象）。
     *
     * @param args 调用参数
     * @return 方法返回值
     * @throws Exception 反射调用异常
     */
    public Object invoke(Object... args) throws Exception {
        if (target == null) {
            throw new IllegalStateException("No target object provided");
        }
        return ReflectUtils.invoke(target, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
    }

    /**
     * 调用方法（显式指定目标对象）。
     *
     * @param target 目标对象实例
     * @param args   调用参数
     * @return 方法返回值
     * @throws Exception 反射调用异常
     */
    public Object invoke(Object target, Object... args) throws Exception {
        return ReflectUtils.invoke(target, method.getName(), method.getReturnType(), method.getParameterTypes(), args);
    }
}