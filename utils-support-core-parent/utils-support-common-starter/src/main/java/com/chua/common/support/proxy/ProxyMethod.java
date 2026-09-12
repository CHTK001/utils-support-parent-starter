package com.chua.common.support.proxy;

import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.objects.describe.MethodDescribe;
import com.chua.common.support.utils.ClassUtils;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 代理方法封装类，用于描述和调用代理对象上的方法。
*
* <p>该类通过 {@link Builder} 构建，封装了目标对象、代理对象、方法及参数等信息，
* 提供了方法匹配、反射调用、参数获取、注解判断等一系列便捷操作。
* 支持方法重载场景下的自动刷新（当首次反射失败时重新查找目标类中的方法定义）。
* </p>
*
* @author CH
* @since 2025/7/20
 */
@Data
@Builder
@Slf4j
public class ProxyMethod {

    /**
    * 被代理的目标对象实例。
    * 即实际执行业务逻辑的对象，代理对象将调用转发至该对象。
     */
    private Object target;

    /**
    * 待执行的反射方法对象。
    * 表示需要被调用的方法定义，由 {@link java.lang.reflect.Method} 描述。
     */
    private Method method;

    /**
    * 调用方法时传入的参数数组。
    * 与方法定义中的参数列表一一对应。
     */
    private Object[] args;

    /**
    * 代理对象实例。
    * 由动态代理生成的代理类实例，用于转发方法调用。
     */
    private Object proxy;

    /**
    * 方法描述信息。
    * 包含方法的增强元数据，如注解信息、返回类型描述等。
     */
    private MethodDescribe methodDescribe;

    /**
    * 对象上下文，用于获取 Bean 实例。
    * <p>
    * 提供对象容器能力，支持从上下文中按类型或名称查找已注册的 Bean。
    * </p>
     */
    private ObjectContext objectContext;

    /**
    * 是否已执行过方法重载刷新。
    * 当首次反射调用失败时，会尝试重新查找目标类中的方法定义并重试，
    * 该标记用于防止无限递归刷新。
     */
    private boolean reloaded;

    /**
    * 判断当前方法名是否与给定名称匹配。
    *
    * @param methodName 待比较的方法名称
    * @return 若方法名相同返回 {@code true}，否则返回 {@code false}
     */
    public boolean is(String methodName) {
        return this.method.getName().equals(methodName);
    }

    /**
    * 在指定客户端对象上反射获取属性值。
    * 通过反射调用 getter 方法或直接字段访问来获取值。
    *
    * @param client 目标客户端对象实例
    * @param <T>    客户端对象类型
    * @return 反射获取到的属性值
     */
    public <T> Object getValue(T client) {
        return ClassUtils.invokeMethod(method, client, args);
    }

    /**
    * 在代理对象上反射获取属性值。
    * 等同于 {@code getValue(proxy)}，方便链式调用。
    *
    * @return 反射获取到的属性值
     */
    public Object getValue() {
        return ClassUtils.invokeMethod(method, proxy, args);
    }

    /**
    * 判断方法是否有返回值。
    * 检查方法的返回类型是否为 {@code void} 或 {@code Void}。
    *
    * @return 若有实际返回值返回 {@code true}，否则返回 {@code false}
     */
    public boolean hasReturnValue() {
        return method.getReturnType() != void.class && method.getReturnType() != Void.class;
    }

    /**
    * 在指定 Bean 上执行当前方法。
    * 使用构建时传入的参数数组进行调用，
    * 若参数为 {@code null} 则替换为空数组。
    *
    * @param bean 目标 Bean 实例
    * @return 方法执行结果
    * @throws InvocationTargetException 当被调用的方法抛出异常时
    * @throws IllegalAccessException    当无法访问该方法时
     */
    public Object invoke(Object bean) throws InvocationTargetException, IllegalAccessException {
        return invoke(bean, args != null ? args : new Object[0]);
    }

    /**
    * 在指定 Bean 上使用给定参数执行当前方法。
    * <p>
    * 当首次反射调用失败时，会自动尝试重新查找目标类中匹配的方法定义（支持类结构变更场景），
    * 查找成功后使用 {@link ClassUtils#invoke} 再次执行，并标记 {@link #reloaded} 防止递归。
    * </p>
    *
    * @param bean 目标 Bean 实例
    * @param args 调用参数数组
    * @return 方法执行结果
    * @throws InvocationTargetException 当被调用的方法抛出异常时
    * @throws IllegalAccessException    当无法访问该方法时
     */
    public Object invoke(Object bean, Object[] args) throws InvocationTargetException, IllegalAccessException {
        if (method == null) {
            return null;
        }

        ClassUtils.setAccessible(method);
        try {
            return ClassUtils.invokeMethod(method, bean, args);
        } catch (Exception e) {
            log.warn("方法调用失败，准备尝试刷新方法定义: {}", e.getMessage());
            if (reloaded) {
                return null;
            }

            // 重新查找方法定义（支持热加载场景）
            method = ClassUtils.findMethod(bean.getClass(), method.getName(), method.getParameterTypes());
            if (method == null) {
                throw e;
            }

            try {
                return ClassUtils.invokeMethod(method, bean, args);
            } finally {
                reloaded = true;
            }
        }
    }

    /**
    * 判断当前方法是否为接口的默认方法（Java 8+）。
    * 默认方法是在接口中使用 {@code default} 关键字定义并包含方法体的方法。
    *
    * @return 若是默认方法返回 {@code true}，否则返回 {@code false}
     */
    public boolean isDefault() {
        return method.isDefault();
    }

    /**
    * 执行接口的默认方法。
    * 通过 {@link ClassUtils#invokeDefaultMethod} 调用目标对象上的默认方法实现。
    *
    * @return 默认方法执行结果
     */
    public Object doDefault() {
        return ClassUtils.invokeDefaultMethod(method, target, args);
    }

    /**
    * 在指定 Bean 上强制执行当前方法（设置可访问标志）。
    * 先调用 {@code setAccessible(true)} 再执行反射调用，
    * 适用于访问私有或受保护方法的场景。
    *
    * @param bean 目标 Bean 实例
    * @return 方法执行结果
     */
    public Object execute(Object bean) {
        method.setAccessible(true);
        return ClassUtils.invokeMethod(method, bean, args);
    }

    /**
    * 获取方法上指定类型的注解。
    * 使用 {@link Method#getDeclaredAnnotation(Class)} 获取直接声明在该方法上的注解。
    *
    * @param annotationType 注解类型
    * @param <A>            注解类型参数
    * @return 若存在则返回注解实例，否则返回 {@code null}
     */
    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        return method.getDeclaredAnnotation(annotationType);
    }

    /**
    * 判断方法是否包含指定类型的注解。
    *
    * @param annotationType 注解类型
    * @return 若该方法上存在该注解返回 {@code true}，否则返回 {@code false}
     */
    public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
        return method.isAnnotationPresent(annotationType);
    }

    /**
    * 获取方法参数名与参数值的映射关系。
    * 根据当前方法的参数名称与构建时传入的参数值 {@link #args} 一一对应组装。
    * 若参数为 {@code null} 则返回空 映射。
    *
    * @return 参数名-参数值映射表，顺序与参数定义一致
     */
    public Map<String, Object> getParameters() {
        if (args == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> params = new HashMap<>(args.length);
        Parameter[] parameters = method.getParameters();

        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            params.put(parameter.getName(), args[i]);
        }

        return params;
    }

    /**
    * 获取当前方法的返回类型。
    *
    * @return 返回类型的 类 对象
     */
    public Class<?> getReturnType() {
        return method.getReturnType();
    }

    /**
    * 获取当前方法的名称。
    *
    * @return 方法名字符串
     */
    public String getMethodName() {
        return method.getName();
    }

    /**
    * 获取当前方法的所有参数类型。
    *
    * @return 参数类型数组
     */
    public Class<?>[] getParameterTypes() {
        return method.getParameterTypes();
    }

    /**
    * 获取当前方法的参数数量。
    *
    * @return 参数个数
     */
    public int getParameterCount() {
        return method.getParameterCount();
    }

    /**
    * 获取当前方法的返回类型（与 {@link #getReturnType()} 等效）。
    *
    * @return 返回类型的 类 对象
     */
    public Class<?> returnType() {
        return method.getReturnType();
    }
}
