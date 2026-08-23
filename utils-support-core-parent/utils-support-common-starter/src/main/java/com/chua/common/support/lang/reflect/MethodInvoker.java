package com.chua.common.support.lang.reflect;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.constant.Projects;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * 方法调用器，统一封装 Java 方法调用，优先使用 MethodHandle 以提供更高性能。
 *
 * <p>
 * 根据 JDK 版本自动选择最优调用策略，在不改变调用方代码的前提下透明提升性能。
 * <ul>
 *   <li>性能优先：JDK 9+ 自动选择 MethodHandle，JDK 8 降级为反射调用</li>
 *   <li>兼容性好：完全兼容 JDK 8 及以上版本</li>
 *   <li>使用简单：一行代码即可完成方法调用，支持返回值类型转换</li>
 *   <li>自动缓存：MethodHandle 实例自动缓存，重复调用零开销</li>
 * </ul>
 * </p>
 *
 * <h3>JDK 版本策略</h3>
 * <ul>
 *   <li>JDK 9+：优先使用 MethodHandle，性能优于反射</li>
 *   <li>JDK 8：使用标准反射调用（java.lang.reflect.Method）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 调用实例方法
 * Object result = MethodInvoker.invoke(method, target, arg1, arg2);
 *
 * // 调用静态方法（target 传 null）
 * Object result = MethodInvoker.invoke(method, null, arg1, arg2);
 *
 * // 带返回值类型转换
 * String result = MethodInvoker.invoke(method, target, String.class, arg1);
 * }</pre>
 *
 * @version 1.0.0
 * @author CH
 * @since 2025/12/03
 */
@Slf4j
public final class MethodInvoker {

    /**
     * 方法句柄缓存，避免重复创建 MethodHandle
     * 使用 ConcurrentReferenceHashMap 实现弱引用缓存，防止内存泄漏
     */
    private static final Map<Method, MethodHandle> METHOD_HANDLE_CACHE = new ConcurrentReferenceHashMap<>(512);

    /**
     * MethodHandles.Lookup 实例，用于创建 MethodHandle
     */
    private static final MethodHandles.Lookup LOOKUP;

    /**
     * 是否优先使用 MethodHandle 调用（JDK 9+ 启用）
     */
    private static final boolean USE_METHOD_HANDLE;

    static {
        LOOKUP = MethodHandles.lookup();
        // JDK 9+ 启用 MethodHandle，否则降级为反射
        USE_METHOD_HANDLE = Projects.getJdkMajorVersion() >= 9;
        if (USE_METHOD_HANDLE) {
            if (log.isDebugEnabled()) {
                log.debug("MethodInvoker: 使用 MethodHandle 调用，JDK {}", Projects.getJdkMajorVersion());
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("MethodInvoker: 使用反射调用，JDK {}", Projects.getJdkMajorVersion());
            }
        }
    }

    /**
     * 私有构造方法，禁止实例化工具类
     */
    private MethodInvoker() {
    }

    /**
     * 调用指定方法，自动选择 MethodHandle 或反射策略
     * <p>
     * 根据 JDK 版本自动选择最优调用方式：
     * <ul>
     *   <li>JDK 9+：MethodHandle（高性能）</li>
     *   <li>JDK 8：标准反射调用</li>
     * </ul>
     * </p>
     *
     * @param method 要调用的方法对象
     * @param target 目标对象（静态方法传 null）
     * @param args   方法参数
     * @return 方法返回值
     * @throws RuntimeException 调用出错时包装抛出运行时异常
     */
    public static Object invoke(Method method, Object target, Object... args) {
        if (method == null) {
            return null;
        }

        // 确保方法可访问（处理私有方法）
        if (!method.isAccessible()) {
            method.setAccessible(true);
        }

        if (USE_METHOD_HANDLE) {
            return invokeWithMethodHandle(method, target, args);
        } else {
            return invokeWithReflection(method, target, args);
        }
    }

    /**
     * 调用指定方法并自动转换返回值为指定类型
     *
     * @param method     要调用的方法对象
     * @param target     目标对象（静态方法传 null）
     * @param returnType 返回值目标类型
     * @param args       方法参数
     * @param <T>        返回值泛型类型
     * @return 转换后的返回值
     */
    public static <T> T invoke(Method method, Object target, Class<T> returnType, Object... args) {
        Object result = invoke(method, target, args);
        return Converter.convertIfNecessary(result, returnType);
    }

    /**
     * 通过类名和方法名调用静态方法
     *
     * @param clazz          目标类
     * @param methodName     方法名
     * @param parameterTypes 参数类型数组
     * @param args           方法参数
     * @return 方法返回值，找不到方法时返回 null
     */
    public static Object invokeStatic(Class<?> clazz, String methodName, Class<?>[] parameterTypes, Object... args) {
        Method method = ClassUtils.findDeclaredMethod(clazz, methodName, parameterTypes);
        if (method == null) {
            log.error("未找到静态方法：{}.{}", clazz.getName(), methodName);
            return null;
        }
        return invoke(method, null, args);
    }

    /**
     * 使用 MethodHandle 调用方法，失败时自动降级为反射
     *
     * @param method 方法对象
     * @param target 目标对象
     * @param args   参数列表
     * @return 调用结果
     */
    private static Object invokeWithMethodHandle(Method method, Object target, Object... args) {
        try {
            MethodHandle handle = getOrCreateMethodHandle(method);
            if (handle == null) {
                // 获取 MethodHandle 失败，降级为反射调用
                return invokeWithReflection(method, target, args);
            }

            // 根据静态/实例方法选择不同调用方式
            if (Modifier.isStatic(method.getModifiers())) {
                return handle.invokeWithArguments(args);
            } else {
                if (target == null) {
                     log.warn("实例方法的目标对象为 null：{}", method);
                    return null;
                }
                return handle.bindTo(target).invokeWithArguments(args);
            }
        } catch (Throwable e) {
            if (log.isDebugEnabled()) {
                 log.debug("MethodHandle 调用异常，降级为反射：{}", method.getName(), e);
            }
            // 降级为反射
            return invokeWithReflection(method, target, args);
        }
    }

    /**
     * 使用标准反射调用方法
     *
     * @param method 方法对象
     * @param target 目标对象
     * @param args   参数列表
     * @return 调用结果
     */
    private static Object invokeWithReflection(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (Exception e) {
            log.error("反射调用失败：{}", method.getName(), e);
            throw new RuntimeException("反射调用失败：" + method.getName(), e);
        }
    }

    /**
     * 从缓存获取或创建方法对应的 MethodHandle
     *
     * @param method 方法对象
     * @return MethodHandle 实例，创建失败时返回 null
     */
    private static MethodHandle getOrCreateMethodHandle(Method method) {
        return METHOD_HANDLE_CACHE.computeIfAbsent(method, m -> {
            try {
                return LOOKUP.unreflect(m);
            } catch (IllegalAccessException e) {
                if (log.isDebugEnabled()) {
                     log.debug("无法创建 MethodHandle：{}", m.getName(), e);
                }
                return null;
            }
        });
    }

    /**
     * 查找指定类的实例方法句柄
     *
     * @param clazz          目标类
     * @param methodName     方法名
     * @param returnType     返回值类型
     * @param parameterTypes 参数类型列表
     * @return MethodHandle 实例，未找到时返回 null
     */
    public static MethodHandle findMethodHandle(Class<?> clazz, String methodName, 
                                                  Class<?> returnType, Class<?>... parameterTypes) {
        try {
            MethodType methodType = MethodType.methodType(returnType, parameterTypes);
            return LOOKUP.findVirtual(clazz, methodName, methodType);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                 log.debug("未找到实例方法句柄：{}.{}", clazz.getName(), methodName, e);
            }
            return null;
        }
    }

    /**
     * 查找指定类的静态方法句柄
     *
     * @param clazz          目标类
     * @param methodName     方法名
     * @param returnType     返回值类型
     * @param parameterTypes 参数类型列表
     * @return MethodHandle 实例，未找到时返回 null
     */
    public static MethodHandle findStaticMethodHandle(Class<?> clazz, String methodName,
                                                       Class<?> returnType, Class<?>... parameterTypes) {
        try {
            MethodType methodType = MethodType.methodType(returnType, parameterTypes);
            return LOOKUP.findStatic(clazz, methodName, methodType);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                 log.debug("未找到静态方法句柄：{}.{}", clazz.getName(), methodName, e);
            }
            return null;
        }
    }

    /**
     * 清空 MethodHandle 缓存
     */
    public static void clearCache() {
        METHOD_HANDLE_CACHE.clear();
        if (log.isDebugEnabled()) {
            log.debug("MethodInvoker 缓存已清空");
        }
    }

    /**
     * 获取当前缓存中的 MethodHandle 数量
     *
     * @return 缓存大小
     */
    public static int getCacheSize() {
        return METHOD_HANDLE_CACHE.size();
    }

    /**
     * 判断当前是否使用 MethodHandle 调用策略
     *
     * @return true 表示使用 MethodHandle，false 表示使用反射
     */
    public static boolean isUsingMethodHandle() {
        return USE_METHOD_HANDLE;
    }
}

