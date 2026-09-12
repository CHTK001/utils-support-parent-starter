package com.chua.common.support.reflection;

import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
* 统一反射工具类 — 基于 方法处理 + lambdametafactory 实现。
*
* <p>所有反射操作必须通过本类完成，禁止在业务代码中直接调用 {@code java.lang.reflect} API。</p>
*
* <h3>核心能力</h3>
* <ul>
*   <li>方法调用：{@link #invoke}, {@link #invokeStatic}, {@link #invokeMethod}</li>
*   <li>字段读写：{@link #getField}, {@link #setField}, {@link #findField}</li>
*   <li>实例化：{@link #instantiate}, {@link #forName}</li>
*   <li>动态代理：{@link #newProxy}</li>
*   <li>Lambda 工厂：{@link #asFunctionalInterface}</li>
* </ul>
*
* <h3>缓存策略</h3>
* <p>所有 MethodHandle 和 FieldHandle 均按 (class, name, paramTypes) 键缓存，首次查找后零开销。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class ReflectUtils {


    // ==================== 缓存 ====================

    /** 方法处理 缓存：键 = 类名称 + "." + 方法名称 + 参数类型 */
    private static final ConcurrentMap<String, MethodHandle> METHOD_HANDLE_CACHE = new ConcurrentHashMap<>(512);

    /** 字段 getter 方法处理 缓存 */
    private static final ConcurrentMap<String, MethodHandle> FIELD_GETTER_CACHE = new ConcurrentHashMap<>(256);

    /** 字段 setter 方法处理 缓存 */
    private static final ConcurrentMap<String, MethodHandle> FIELD_SETTER_CACHE = new ConcurrentHashMap<>(256);

    /** 类加载 缓存 */
    private static final ConcurrentMap<String, Class<?>> CLASS_NAME_CACHE = new ConcurrentHashMap<>(256);

    /** 方法处理.Lookup 实例 */
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** 私有构造，禁止实例化 */
    private ReflectUtils() {
    }

    // ==================== Class 加载 ====================

    /**
    * 加载类（带缓存）。
    *
    * @param className 全限定类名
    * @return Class 对象，加载失败返回 空
     */
    public static Class<?> forName(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        return CLASS_NAME_CACHE.computeIfAbsent(className, it -> {
            try {
                return Class.forName(it);
            } catch (ClassNotFoundException e) {
                log.debug("[ReflectUtils] 类加载失败: {}", className);
                return null;
            }
        });
    }

    /**
    * 加载类（指定 类加载，带缓存）。
    *
    * @param className     全限定类名
    * @param classLoader   类加载器
    * @return Class 对象，加载失败返回 空
     */
    public static Class<?> forName(String className, ClassLoader classLoader) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        String key = className + "@" + (classLoader == null ? "system" : System.identityHashCode(classLoader));
        return CLASS_NAME_CACHE.computeIfAbsent(key, it -> {
            try {
                int idx = it.lastIndexOf('@');
                String clsName = it.substring(0, idx);
                ClassLoader cl = idx >= 0 ? classLoader : Thread.currentThread().getContextClassLoader();
                // initialize=true：触发静态块（SPI 注册器依赖类初始化执行 registerAll 等逻辑）
                return cl != null ? Class.forName(clsName, true, cl) : Class.forName(clsName);
            } catch (ClassNotFoundException e) {
                log.debug("[ReflectUtils] 类加载失败: {}", className);
                return null;
            }
        });
    }

    /**
    * 加载类并校验其是否兼容指定的返回类型（泛型安全，避免外部强转）。
    *
    * @param className  全限定类名
    * @param returnType 期望的返回类型
    * @param <T>        泛型类型参数
    * @return 类型兼容时返回对应 {@link Class}，加载失败或不兼容返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> forName(String className, Class<T> returnType) {
        Class<?> aClass = forName(className);
        if (aClass == null || !returnType.isAssignableFrom(aClass)) {
            return null;
        }
        return (Class<T>) (Class<?>) aClass;
    }

    /**
    * 加载类（指定 类加载）并校验其是否兼容指定的返回类型（泛型安全，避免外部强转）。
    *
    * @param className   全限定类名
    * @param classLoader 类加载器
    * @param returnType  期望的返回类型
    * @param <T>         泛型类型参数
    * @return 类型兼容时返回对应 {@link Class}，加载失败或不兼容返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> forName(String className, ClassLoader classLoader, Class<T> returnType) {
        Class<?> aClass = forName(className, classLoader);
        if (aClass == null || !returnType.isAssignableFrom(aClass)) {
            return null;
        }
        return (Class<T>) (Class<?>) aClass;
    }

    // ==================== 方法调用 ====================

    /**
    * 调用实例方法（方法处理 路径）。
    *
    * @param target       目标对象，静态方法传 空
    * @param methodName   方法名
    * @param returnType   返回类型
    * @param argTypes     参数类型数组
    * @param args         参数值
    * @return 方法返回值
     */
    public static Object invoke(Object target, String methodName, Class<?> returnType,
                                Class<?>[] argTypes, Object... args) {
        try {
            Class<?> targetClass = target == null ? Object.class : target.getClass();
            MethodHandle handle = findMethodHandle(targetClass, methodName, returnType, argTypes);
            if (handle == null) {
                log.warn("[ReflectUtils] 未找到方法: {}.{}({})",
                        targetClass.getSimpleName(), methodName, formatTypes(argTypes));
                return null;
            }
            if (target != null) {
                handle = handle.bindTo(target);
            }
            return handle.invokeWithArguments(args);
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 方法调用异常: {}.{}({})",
                    target == null ? "?" : target.getClass().getSimpleName(), methodName, formatTypes(argTypes), e);
            return null;
        }
    }

    /**
    * 调用实例方法（自动推断参数类型）。
    *
    * @param target     目标对象
    * @param methodName 方法名
    * @param returnType 返回类型
    * @param args       参数值
    * @return 方法返回值
     */
    public static Object invoke(Object target, String methodName, Class<?> returnType, Object... args) {
        Class<?>[] argTypes = inferArgTypes(args);
        return invoke(target, methodName, returnType, argTypes, args);
    }

    /**
    * 调用无参实例方法。
    *
    * @param target     目标对象
    * @param methodName 方法名
    * @param returnType 返回类型
    * @return 方法返回值
     */
    public static Object invoke(Object target, String methodName, Class<?> returnType) {
        return invoke(target, methodName, returnType, new Class<?>[0], new Object[0]);
    }

    /**
    * 调用静态方法。
    *
    * @param clazz      目标类
    * @param methodName 方法名
    * @param returnType 返回类型
    * @param argTypes   参数类型数组
    * @param args       参数值
    * @return 方法返回值
     */
    public static Object invokeStatic(Class<?> clazz, String methodName, Class<?> returnType,
                                      Class<?>[] argTypes, Object... args) {
        try {
            MethodHandle handle = findStaticMethodHandle(clazz, methodName, returnType, argTypes);
            if (handle == null) {
                log.warn("[ReflectUtils] 未找到静态方法: {}.{}({})",
                        clazz.getSimpleName(), methodName, formatTypes(argTypes));
                return null;
            }
            return handle.invokeWithArguments(args);
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 静态方法调用异常: {}.{}({})",
                    clazz.getSimpleName(), methodName, formatTypes(argTypes), e);
            return null;
        }
    }

    /**
    * 通过类名字符串调用静态方法。
    *
    * @param className  全限定类名
    * @param methodName 方法名
    * @param returnType 返回类型
    * @param args       参数值
    * @return 方法返回值
     */
    public static Object invokeStatic(String className, String methodName, Class<?> returnType, Object... args) {
        Class<?> clazz = forName(className);
        if (clazz == null) {
            log.warn("[ReflectUtils] 类不存在: {}", className);
            return null;
        }
        return invokeStatic(clazz, methodName, returnType, inferArgTypes(args), args);
    }

    /**
    * 获取 方法处理（带缓存）。
    *
    * @param clazz        目标类
    * @param methodName   方法名
    * @param returnType   返回类型
    * @param paramTypes   参数类型
    * @return MethodHandle，找不到返回 空
     */
    public static MethodHandle findMethodHandle(Class<?> clazz, String methodName,
                                                 Class<?> returnType, Class<?>... paramTypes) {
        if (clazz == null || methodName == null) {
            return null;
        }
        String key = buildMethodKey(clazz, methodName, returnType, paramTypes);
        try {
            return METHOD_HANDLE_CACHE.computeIfAbsent(key, k -> {
                try {
                    MethodType mt = MethodType.methodType(returnType, paramTypes);
                    return LOOKUP.findVirtual(clazz, methodName, mt);
                } catch (NoSuchMethodException | IllegalAccessException e) {
 // 尝试 公共 方法
                    try {
                        MethodType mt = MethodType.methodType(returnType, paramTypes);
                        return LOOKUP.findStatic(clazz, methodName, mt);
                    } catch (NoSuchMethodException | IllegalAccessException ex) {
                        // 返回类型容错：请求的返回类型与方法实际类型不一致（如注解代理 value() 实际返回 String
 // 而调用方请求 对象.类），查找虚拟 为精确类型匹配会失败；
 // 按实际返回类型查找后原样返回，由 invokewith参数 自动适配
                        return findWithActualReturnType(clazz, methodName, paramTypes);
                    }
                }
            });
        } catch (Exception e) {
            log.debug("[ReflectUtils] 获取 MethodHandle 失败: {}", key, e);
            return null;
        }
    }

    /**
    * 获取静态 方法处理（带缓存）。
     */
    public static MethodHandle findStaticMethodHandle(Class<?> clazz, String methodName,
                                                       Class<?> returnType, Class<?>... paramTypes) {
        if (clazz == null || methodName == null) {
            return null;
        }
        String key = buildStaticMethodKey(clazz, methodName, returnType, paramTypes);
        try {
            return METHOD_HANDLE_CACHE.computeIfAbsent(key, k -> {
                try {
                    MethodType mt = MethodType.methodType(returnType, paramTypes);
                    return LOOKUP.findStatic(clazz, methodName, mt);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    return null;
                }
            });
        } catch (Exception e) {
            log.debug("[ReflectUtils] 获取静态 MethodHandle 失败: {}", key, e);
            return null;
        }
    }

    // ==================== 字段访问 ====================

    /**
    * 读取字段值（沿继承链查找，结果缓存）。
    *
    * @param target    目标对象
    * @param fieldName 字段名
    * @return 字段值，找不到或异常返回 空
     */
    public static Object getField(Object target, String fieldName) {
        if (target == null || fieldName == null) {
            return null;
        }
        try {
            MethodHandle handle = findFieldGetter(target.getClass(), fieldName);
            if (handle == null) {
                log.debug("[ReflectUtils] 字段不存在: {}.{}", target.getClass().getSimpleName(), fieldName);
                return null;
            }
            return handle.invoke(target);
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 读取字段异常: {}.{}", target.getClass().getSimpleName(), fieldName, e);
            return null;
        }
    }

    /**
    * 写入字段值（沿继承链查找，结果缓存）。
    *
    * @param target    目标对象
    * @param fieldName 字段名
    * @param value     新值
    * @return 是否成功
     */
    public static boolean setField(Object target, String fieldName, Object value) {
        if (target == null || fieldName == null) {
            return false;
        }
        try {
            MethodHandle handle = findFieldSetter(target.getClass(), fieldName);
            if (handle == null) {
                log.debug("[ReflectUtils] 字段不存在: {}.{}", target.getClass().getSimpleName(), fieldName);
                return false;
            }
            handle.invoke(target, value);
            return true;
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 写入字段异常: {}.{}", target.getClass().getSimpleName(), fieldName, e);
            return false;
        }
    }

    /**
    * 查找字段（沿继承链），返回 方法处理 getter。
    * @param clazz clazz
    * @param fieldName 字段名称
    * @return find字段getter的结果
     */
    private static MethodHandle findFieldGetter(Class<?> clazz, String fieldName) {
        String key = "GET:" + clazz.getName() + "|" + fieldName;
        try {
            return FIELD_GETTER_CACHE.computeIfAbsent(key, k -> {
                Class<?> c = clazz;
                while (c != null) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField(fieldName);
 // Java 9+ 私募lookup入：解决内部类/嵌套类 私募 字段的 方法处理 权限问题
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, LOOKUP);
                        return lookup.unreflectGetter(f);
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        c = c.getSuperclass();
                    }
                }
                return null;
            });
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 查找字段（沿继承链），返回 方法处理 setter。
    * @param clazz clazz
    * @param fieldName 字段名称
    * @return find字段setter的结果
     */
    private static MethodHandle findFieldSetter(Class<?> clazz, String fieldName) {
        String key = "SET:" + clazz.getName() + "|" + fieldName;
        try {
            return FIELD_SETTER_CACHE.computeIfAbsent(key, k -> {
                Class<?> c = clazz;
                while (c != null) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField(fieldName);
 // Java 9+ 私募lookup入：解决内部类/嵌套类 私募 字段的 方法处理 权限问题
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, LOOKUP);
                        return lookup.unreflectSetter(f);
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        c = c.getSuperclass();
                    }
                }
                return null;
            });
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 查找字段（沿继承链），返回 Java.lang.reflect.字段（用于兼容场景）。
    *
    * @param clazz   目标类
    * @param name    字段名
    * @return Field 对象，找不到返回 空
     */
    public static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /**
    * 查找包含子串的字段名（用于 Kafka broker 等模糊匹配场景）。
    *
    * @param clazz         目标类
    * @param nameSubstring 字段名字符串片段
    * @return 第一个匹配的字段值，找不到返回 空
    * @param target Target
     */
    public static Object findFieldBySubstring(Object target, String nameSubstring) {
        if (target == null || nameSubstring == null) {
            return null;
        }
        try {
            for (java.lang.reflect.Field f : target.getClass().getDeclaredFields()) {
                if (f.getName().toLowerCase().contains(nameSubstring.toLowerCase())) {
                    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(f.getDeclaringClass(), LOOKUP);
                    MethodHandle handle = lookup.unreflectGetter(f);
            return handle.invoke(target);
                }
            }
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 按子串查找字段异常: {}", nameSubstring, e);
        }
        return null;
    }

    // ==================== 实例化 ====================

    /**
    * 无参实例化（方法处理 路径）。
    *
    * @param clazz 目标类
    * @param <T>   返回类型
    * @return 实例，失败返回 空
     */
    @SuppressWarnings("unchecked")
    public static <T> T instantiate(Class<T> clazz) {
        if (clazz == null) {
            return null;
        }
        try {
            MethodHandle handle = findConstructorHandle(clazz);
            if (handle == null) {
                return null;
            }
            /* invoke 会自动做 asType 适配；invokeExact 在泛型擦除后调用点签名
              * 为 ()对象，与构造器句柄类型 ()X 不匹配，必然抛 wrong方法类型异常 */
            return clazz.cast(handle.invoke());
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 实例化失败: {}", clazz.getName(), e);
            return null;
        }
    }

    /**
    * 带参实例化（方法处理 路径）。
    *
    * @param clazz  目标类
    * @param args   构造参数
    * @param <T>    返回类型
    * @return 实例，失败返回 空
     */
    @SuppressWarnings("unchecked")
    public static <T> T instantiate(Class<T> clazz, Object... args) {
        if (clazz == null) {
            return null;
        }
        try {
            Class<?>[] argTypes = inferArgTypes(args);
            MethodHandle handle = findConstructorHandle(clazz, argTypes);
            if (handle == null) {
                return null;
            }
            return (T) handle.invokeWithArguments(args);
        } catch (Throwable e) {
            log.debug("[ReflectUtils] 带参实例化失败: {}", clazz.getName(), e);
            return null;
        }
    }

    /**
    * 通过类名无参实例化。
    *
    * @param className 全限定类名
    * @param <T>       返回类型
    * @return 实例，失败返回 空
     */
    @SuppressWarnings("unchecked")
    public static <T> T instantiate(String className) {
        Class<?> clazz = forName(className);
        if (clazz == null) {
            return null;
        }
        return (T) instantiate(clazz);
    }

    /**
    * 获取构造器 方法处理（带缓存）。
    * @param clazz clazz
    * @param paramTypes 参数类型
    * @return findconstructor处理的结果
     */
    private static MethodHandle findConstructorHandle(Class<?> clazz, Class<?>... paramTypes) {
        String key = "CTOR:" + clazz.getName() + "@" + formatTypes(paramTypes);
        try {
            return METHOD_HANDLE_CACHE.computeIfAbsent(key, k -> {
                try {
                    MethodType mt = MethodType.methodType(void.class, paramTypes);
                    return LOOKUP.findConstructor(clazz, mt);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    return null;
                }
            });
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 动态代理 ====================

    /**
    * 创建 JDK 动态代理（通过 方法处理 桥接）。
    *
    * @param loader     类加载器
    * @param interfaces 接口数组
    * @param handler    调用处理器
    * @param <T>        代理类型
    * @return 代理实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T newProxy(ClassLoader loader, Class<?>[] interfaces,
                                  java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(loader, interfaces, handler);
    }

    // ==================== LambdaMetafactory ====================

    /**
    * 将 方法处理 适配为函数式接口的 lambda 实例（使用 lambdametafactory）。
    *
    * <p>典型用法：将私有方法或静态方法转换为 {@link Function}/{@link Runnable} 等。</p>
    *
    * <pre>{@code
    *   // 将 ObjIntConsumer<Integer> 适配为 MethodHandle
    *   MethodHandle mh = LOOKUP.findVirtual(SomeClass.class, "process",
    *           MethodType.methodType(void.class, int.class));
    *   ObjIntConsumer<Integer> fn = ReflectUtils.asFunctionalInterface(
    *           mh, ObjIntConsumer.class, Integer.class, int.class);
    * }</pre>rface(
    *           mh, ObjIntConsumer.class, Integer.class, int.class);
    * }</pre>
    *
    * @param implMethod       实现方法的 方法处理（已 bind转为 Target）
    * @param functionalInterface 目标函数式接口 类
    * @param paramTypes       函数式接口方法参数类型（按顺序）
    * @param <T>              函数式接口类型
    * @return lambda 实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T asFunctionalInterface(MethodHandle implMethod,
                                               Class<T> functionalInterface,
                                               Class<?>... paramTypes) {
        try {
            MethodType mt = MethodType.methodType(void.class, paramTypes);
 // 如果 impl方法 返回非 Void Linux Linux，需要 adapt常量 处理
            MethodType implType = implMethod.type();
            if (implType.returnType() != void.class) {
 // 调整为返回 对象 以便 Box/unbox
                implMethod = implMethod.asType(MethodType.methodType(Object.class, paramTypes));
            }
            return functionalInterface.cast(LambdaMetafactory.metafactory(
                    LOOKUP,
                    functionalInterface.getMethod("apply", paramTypes).getName(),
                    MethodType.methodType(functionalInterface),
                    MethodType.methodType(Object.class, paramTypes),
                    implMethod,
                    implMethod.type().changeReturnType(Object.class)
            ).getTarget().invoke());
        } catch (Throwable e) {
            log.debug("[ReflectUtils] LambdaMetafactory 创建失败", e);
            return null;
        }
    }

    /**
    * 将无参 方法处理 适配为 {@link java.util.concurrent.Callable}。
    * @param mh mh
    * @param returnType 返回类型
    * @return asCallable的结果
     */
    @SuppressWarnings("unchecked")
    public static <T> java.util.concurrent.Callable<T> asCallable(MethodHandle mh, Class<T> returnType) {
        try {
            return (java.util.concurrent.Callable<T>) LambdaMetafactory.metafactory(
                    LOOKUP,
                    "call",
                    MethodType.methodType(java.util.concurrent.Callable.class),
                    MethodType.methodType(Object.class),
                    mh,
                    mh.type().changeReturnType(Object.class)
            ).getTarget().invokeExact();        } catch (Throwable e) {
            log.debug("[ReflectUtils] Callable 适配失败", e);
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /**
    * 构建 方法处理 缓存 键。
     */
    /**
    * 返回类型容错查找：请求的返回类型与方法实际类型不一致时，
    * 遍历 公共 方法按名称与参数类型定位，返回实际类型的 方法处理。
    *
    * @param clazz      目标类
    * @param methodName 方法名
    * @param paramTypes 参数类型
    * @return 实际类型的 方法处理；未找到返回 空
     */
    private static MethodHandle findWithActualReturnType(Class<?> clazz, String methodName,
                                                         Class<?>[] paramTypes) {
        for (java.lang.reflect.Method method : clazz.getMethods()) {
            if (!method.getName().equals(methodName)
                    || method.getParameterCount() != paramTypes.length) {
                continue;
            }
            Class<?>[] actual = method.getParameterTypes();
            boolean matched = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!paramTypes[i].isAssignableFrom(actual[i])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                try {
                    return LOOKUP.unreflect(method);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String buildMethodKey(Class<?> clazz, String methodName,
                                          Class<?> returnType, Class<?>... paramTypes) {
        return "MH:" + clazz.getName() + "." + methodName + "@"
                + formatTypes(paramTypes) + "->" + returnType.getName();
    }

    /**
    * 构建静态 方法处理 缓存 键。
     */
    private static String buildStaticMethodKey(Class<?> clazz, String methodName,
                                                Class<?> returnType, Class<?>... paramTypes) {
        return "SMH:" + clazz.getName() + "." + methodName + "@"
                + formatTypes(paramTypes) + "->" + returnType.getName();
    }

    /**
    * 格式化类型数组为字符串。
    * @param types 类型
    * @return 格式化类型的结果
     */
    private static String formatTypes(Class<?>... types) {
        if (types == null || types.length == 0) {
            return "()";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }

    /**
    * 根据参数值推断参数类型数组。
    * @param args 参数
    * @return infer参数类型的结果
     */
    private static Class<?>[] inferArgTypes(Object... args) {
        if (args == null || args.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        return types;
    }

    // ==================== 缓存清理 ====================

    /**
    * 清空所有缓存（调试/测试用）。
     */
    public static void clearCache() {
        METHOD_HANDLE_CACHE.clear();
        FIELD_GETTER_CACHE.clear();
        FIELD_SETTER_CACHE.clear();
        CLASS_NAME_CACHE.clear();
        log.info("[ReflectUtils] 所有缓存已清空");
    }

    /**
    * 返回当前缓存条目数。
    * @return 缓存大小的结果
     */
    public static int cacheSize() {
        return METHOD_HANDLE_CACHE.size() + FIELD_GETTER_CACHE.size() + FIELD_SETTER_CACHE.size();
    }
}
