package com.chua.common.support.objects.describe;

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

    private final String name;
    private final Class<?> returnType;
    private final Class<?>[] parameterTypes;
    private final String[] parameterNames;

    /**
     * 构造方法描述。
     *
     * @param method Java 反射方法对象
     */
    public MethodDescribe(Method method) {
        this.name = method.getName();
        this.returnType = method.getReturnType();
        this.parameterTypes = method.getParameterTypes();
        // 不解析参数名（需要 -parameters 编译参数）
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
}