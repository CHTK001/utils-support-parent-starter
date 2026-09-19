package com.chua.common.support.utils;
import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.function.MethodFilter;
import com.chua.common.support.function.SafeConsumer;
import com.chua.common.support.lang.reflect.MethodInvoker;
import com.chua.common.support.modules.ModuleLoader;
import com.chua.common.support.reflection.ReflectUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import lombok.extern.slf4j.Slf4j;
import java.io.Closeable;
import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.constant.ValueConstant.*;
import static com.chua.common.support.converter.Converter.convertIfPrimitive;
/**
 * 类型与反射辅助工具类，提供类名解析、原始类型/包装类型转换、
 * 运行时实例化、字段与方法访问、泛型信息解析等能力。
 * <p>
 * 该类封装了常见的 Java 反射操作，便于在框架层面统一处理类加载、
 * 构造函数匹配、字段赋值以及方法调用等场景。
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
@SuppressWarnings("ALL")
public class ClassUtils {
    /**
     * 类工具。
     */
    private ClassUtils() {
    }
    /**
     *       .
     * 键             值                    JDK
     * 并发引用哈希映射                512
     */
    private static final Map<String, Boolean> CACHE = new ConcurrentReferenceHashMap<>(512);
    /**
     *             .
     * 键                   值              方法
     * 并发引用哈希映射                512
     */
    private static final Map<Integer, Method> cacheMethod = new ConcurrentReferenceHashMap<>(512);
    /**
     *                   : {@code '$' == {@value}}.
     * 外部$内部        $
     */
    public static final char INNER_CLASS_SEPARATOR_CHAR = '$';
    /**
     *                : {@code '&#x2e;' == {@value}}.
     * com.example.工具        .
     */
    public static final char PACKAGE_SEPARATOR_CHAR = '.';
    /**
     *           JDK                .
     *        Java                                            JDK                                  
     * Java   javax   jdk   oracle     sun
     */
    private static final String[] RT_PACKAGE = new String[]{
            "java.*",
            "javax.*",
            "jdk.*",
            "com.oracle.*",
            "com.sun.*"
    };
    /**
     *                         .
     * 并发引用哈希映射          键             值
     *                 256                                          
     */
    private static final Map<Class<?>, Method[]> DECLARED_METHODS_CACHE = new ConcurrentReferenceHashMap<>(256);
    /**
     *                                           .
     * int.类                                        Integer.类
     * 映射
     */
    public static final Map<Class<?>, Class<?>> PRIMITIVE_PACK = Collections.unmodifiableMap(new HashMap<Class<?>, Class<?>>() {
        {
            put(byte.class, Byte.class);
            put(boolean.class, Boolean.class);
            put(short.class, Short.class);
            put(char.class, Character.class);
            put(int.class, Integer.class);
            put(float.class, Float.class);
            put(long.class, Long.class);
            put(double.class, Double.class);
            put(void.class, Void.class);
        }
    });
    /**
     *                            .
     * Table                                  B   I   D                                                   
     *                                                    
     */
    public static final Table<Object, Class<?>, Class<?>> BASIC_VIRTUAL = HashBasedTable.create();
    static {
        //                                                             
        BASIC_VIRTUAL.put("B", byte.class, Byte.class);
        BASIC_VIRTUAL.put("I", int.class, Integer.class);
        BASIC_VIRTUAL.put("D", double.class, Double.class);
        BASIC_VIRTUAL.put("F", float.class, Float.class);
        BASIC_VIRTUAL.put("C", char.class, Character.class);
        BASIC_VIRTUAL.put("Z", boolean.class, Boolean.class);
        BASIC_VIRTUAL.put("S", short.class, Short.class);
        BASIC_VIRTUAL.put("J", long.class, Long.class);
    }
    /**
     *                         .
     *                                                                                     
     * CGLIB   Spring                Javassist   Apache ibatis
     */
    private static final List<String> PROXY_CLASS_NAMES = Arrays.asList("net.sf.cglib.proxy.Factory"
            // cglib
            , "org.springframework.cglib.proxy.Factory"
            , "javassist.util.proxy.ProxyObject"
            // javassist
            , "org.apache.ibatis.javassist.util.proxy.ProxyObject");
    /**
     *                               .
     * 键             值
     * 并发引用哈希映射                                                  256
     */
    protected static final Map<Class<?>, Type[]> ACTUAL = new ConcurrentReferenceHashMap<>(256);
    /**
     *                                                          .
     * 键             值
     * 并发引用哈希映射                256
     */
    protected static final Map<Class<?>, List<Field>> CLASS_FIELD = new ConcurrentReferenceHashMap<>(256);
    /**
     *                                                                         .
     * 键             值
     * 并发引用哈希映射                256
     */
    protected static final Map<Class<?>, List<Field>> CLASS_FIELD_LOCAL = new ConcurrentReferenceHashMap<>(256);
    /**
     *                                                                         .
     * 键             值
     * 并发引用哈希映射                256
     */
    protected static final Map<Class<?>, List<Method>> CLASS_METHOD = new ConcurrentReferenceHashMap<>(256);
    /**
     *                                                                         .
     * 键             值
     * 并发引用哈希映射                256
     */
    protected static final Map<Class<?>, List<Method>> CLASS_METHOD_LOCAL = new ConcurrentReferenceHashMap<>(256);
    /**
     *                                     .
     * Integer.类                               int.类
     * identity哈希映射                                         9
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_TYPE_MAP = new IdentityHashMap<>(9);
    /**
     *                                     .
     * int.类                                        Integer.类
     * identity哈希映射                                         9
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TYPE_TO_WRAPPER_MAP = new IdentityHashMap<>(9);
    /**
     *                                     .
     * "int"                                        int.类
     * 并发引用哈希映射                32
     */
    private static final Map<String, Class<?>> PRIMITIVE_TYPE_NAME_MAP = new ConcurrentReferenceHashMap<>(32);
    /**
     * Java           类                .
     * Java           字符串   Integer   列表
     * 并发引用哈希映射                64
     */
    private static final Map<String, Class<?>> COMMON_CLASS_CACHE = new ConcurrentReferenceHashMap<>(64);
    /**
     * 类                      .
     * for名称
     * 并发引用哈希映射                1024
     */
    private static final Map<String, Class<?>> CLASS_NAME_CACHE = new ConcurrentReferenceHashMap<>(1024);
    /**
     *                      : {@code "[]"}.
     * "字符串[]"     "int[]"
     */
    public static final String ARRAY_SUFFIX = "[]";
    /**
     *                            : {@code CommonConstant.SYMBOL_LEFT_SQUARE_BRACKET}.
     *        JVM                                      "["
     */
    private static final String INTERNAL_ARRAY_PREFIX = SYMBOL_LEFT_SQUARE_BRACKET;
    /**
     *                               : {@code "[L"}.
     * "[Ljava.lang.字符串;"
     */
    private static final String NON_PRIMITIVE_ARRAY_PREFIX = "[L";
    /**
     *                               .
     *                                                 
     */
    private static final Class<?>[] EMPTY_CLASS_ARRAY = {};
    /**
     *                : {@code '.'}.
     *                                  
     */
    private static final char PACKAGE_SEPARATOR = '.';
    /**
     *                : {@code '/'}.
     *                                        
     */
    private static final char PATH_SEPARATOR = '/';
    /**
     *                   : {@code '$'}.
     * 外部$内部
     */
    private static final char NESTED_CLASS_SEPARATOR = '$';
    /**
     * Java                   .
     *        Java                                                                                     
     *          Serializable   Cloneable   Comparable    
    private static final long serialVersionUID = 1L;
     */
    private static final Set<Class<?>> JAVA_LANGUAGE_INTERFACES;
    /**
     *                                                       .
     *     JVM                                "I"                               "int"   
     *                               
     */
    private static final Map<String, String> REVERSE_ABBREVIATION_MAP;
 // Feed abbreviation 映射
    static {
        final Map<String, String> m = new HashMap<>();
        m.put("int", "I");
        m.put("boolean", "Z");
        m.put("float", "F");
        m.put("long", "J");
        m.put("short", "S");
        m.put("byte", "B");
        m.put("double", "D");
        m.put("char", "C");
        final Map<String, String> r = new HashMap<>();
        for (final Map.Entry<String, String> e : m.entrySet()) {
            r.put(e.getValue(), e.getKey());
        }
        REVERSE_ABBREVIATION_MAP = Collections.unmodifiableMap(r);
    }
    static {
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Boolean.class, boolean.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Byte.class, byte.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Character.class, char.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Double.class, double.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Float.class, float.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Integer.class, int.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Long.class, long.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Short.class, short.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Void.class, void.class);
 // 映射 entry 迭代 是否 less expensive 转为 初始化 than foreach with lambdas
        for (Map.Entry<Class<?>, Class<?>> entry : PRIMITIVE_WRAPPER_TYPE_MAP.entrySet()) {
            PRIMITIVE_TYPE_TO_WRAPPER_MAP.put(entry.getValue(), entry.getKey());
            registerCommonClasses(entry.getKey());
        }
        Set<Class<?>> primitiveTypes = new HashSet<>(32);
        primitiveTypes.addAll(PRIMITIVE_WRAPPER_TYPE_MAP.values());
        Collections.addAll(primitiveTypes, boolean[].class, byte[].class, char[].class,
                double[].class, float[].class, int[].class, long[].class, short[].class);
        for (Class<?> primitiveType : primitiveTypes) {
            PRIMITIVE_TYPE_NAME_MAP.put(primitiveType.getName(), primitiveType);
        }
        registerCommonClasses(Boolean[].class, Byte[].class, Character[].class, Double[].class,
                Float[].class, Integer[].class, Long[].class, Short[].class);
        registerCommonClasses(Number.class, Number[].class, String.class, String[].class,
                Class.class, Class[].class, Object.class, Object[].class);
        registerCommonClasses(Throwable.class, Exception.class, RuntimeException.class,
                Error.class, StackTraceElement.class, StackTraceElement[].class);
        registerCommonClasses(Enum.class, Iterable.class, Iterator.class, Enumeration.class,
                Collection.class, List.class, Set.class, Map.class, Map.Entry.class, Optional.class);
        Class<?>[] javaLanguageInterfaceArray = {Serializable.class, Externalizable.class,
                Closeable.class, AutoCloseable.class, Cloneable.class, Comparable.class};
        registerCommonClasses(javaLanguageInterfaceArray);
        JAVA_LANGUAGE_INTERFACES = new HashSet<>(Arrays.asList(javaLanguageInterfaceArray));
    }
    /**
     * 将常见 Java 类型注册到名称缓存中，便于后续通过类名快速定位。
     *
     * @param commonClasses 需要注册的常见类集合
     * @since 1.0
     */
    private static void registerCommonClasses(Class<?>... commonClasses) {
        for (Class<?> clazz : commonClasses) {
            COMMON_CLASS_CACHE.put(clazz.getName(), clazz);
        }
    }
    /**
     * 判断给定类型是否为 {@code void}、{@link Void} 或空值。
     *
     * @param value 待判断的类对象
     * @param <T> 类型参数
     * @return 如果类型为 {@code void}、{@link Void} 或 {@code null}，则返回 {@code true}
     * @since 1.0
     */
    public static <T> boolean isVoid(Class<T> value) {
        return null == value || value == void.class || value == Void.class;
    }
    /**
     * 判断给定对象是否为空值或其类型表示 {@code void}。
     *
     * @param value 待判断的对象
     * @param <T> 类型参数
     * @return 如果对象为 {@code null}，或其实际类型为 {@code void}/{@link Void}，则返回 {@code true}
     * @since 1.0
     */
    public static <T> boolean isVoid(T value) {
        return null == value || isVoid(toType(value));
    }
    /**
     * 判断给定对象是否非空（非 {@code null}）。
     *
     * @param value 待检查的对象
     * @param <T> 类型参数
     * @return 如果 {@code value} 非 {@code null} 则返回 {@code true}
     * @since 4.0.0.43
     */
    public static <T> boolean isPresent(T value) {
        return value != null;
    }
    /**
     *                                        .
     *
     *                               
     * 1.                                                 
     * 2.                       类工具
     * 3.                                           
     *
     * @return                                                                    null
     * @since 1.0
     */
    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {
        }
        if (cl == null) {
            cl = ClassUtils.class.getClassLoader();
        }
        if (cl == null) {
            try {
                cl = ClassLoader.getSystemClassLoader();
            } catch (Throwable ignored) {
            }
        }
        return cl;
    }
    /**
     * 已被 {@link ScriptDefinition} 主动管理的 类加载 集合。
     * <p>用于避免 GC 时 ClassLoader 泄漏（脚本编译产生的 ClassLoader 持有大量 Metaspace）。
     * 弱引用保证 类加载 不再被业务引用时可被 GC 回收，本集合不阻止回收。</p>
     */
    private static final java.util.Set<java.lang.ref.WeakReference<ClassLoader>> REGISTERED_CLASS_LOADERS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /**
     * 将 类加载 注册到全局弱引用集合中，便于后续清理时遍历释放。
     * <p>主要用于脚本引擎（Groovy/JS/Python 等）动态编译产生的 ClassLoader，
     * 它们持有大量已编译类可能引发 Metaspace 泄漏。
     * 调用方应自行保证 类加载 唯一，否则该方法会被相同 类加载 重复注册。</p>
     *
     * @param classLoader 待注册的 类加载，空 时不处理
     */
    public static void registerClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        REGISTERED_CLASS_LOADERS.add(new java.lang.ref.WeakReference<>(classLoader));
    }
    /**
     * 注销（清理）指定的 类加载。
     *
     * <p>按以下顺序尝试释放：</p>
     * <ol>
     *   <li>若 ClassLoader 实现了 {@link AutoCloseable}，调用 {@link AutoCloseable#close()}</li>
     *   <li>若 ClassLoader 是 {@link URLClassLoader}，调用 {@link URLClassLoader#close()} 关闭</li>
     *   <li>若 ClassLoader 是 {@link Closeable}（JDK 内部某些实现），调用 {@link Closeable#close()}</li>
     * </ol>
     *
     * <p>同时从全局注册集合中移除该 ClassLoader 的弱引用。</p>
     *
     * @param classLoader 待注销的 类加载，空 时不处理
     */
    public static void unregisterClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        // 从全局注册集合中移除
        REGISTERED_CLASS_LOADERS.removeIf(ref -> {
            ClassLoader cl = ref.get();
            return cl == null || cl == classLoader;
        });
        // 释放资源
        try {
            if (classLoader instanceof AutoCloseable) {
                ((AutoCloseable) classLoader).close();
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            if (classLoader instanceof URLClassLoader) {
                ((URLClassLoader) classLoader).close();
            }
        } catch (Exception ignored) {
        }
    }
    /**
     * 清理并注销所有已注册的 类加载。
     * <p>遍历 {@link #REGISTERED_CLASS_LOADERS} 中所有仍然存活（未被 GC）的 ClassLoader，
     * 逐一调用 {@link #unregisterClassLoader(ClassLoader)}。
     * 已被 GC 回收的弱引用直接移除。</p>
     *
     * @return 实际注销的 类加载 数量
     */
    public static int clearAllClassLoaders() {
        int count = 0;
        java.util.Iterator<java.lang.ref.WeakReference<ClassLoader>> it = REGISTERED_CLASS_LOADERS.iterator();
        while (it.hasNext()) {
            java.lang.ref.WeakReference<ClassLoader> ref = it.next();
            ClassLoader cl = ref.get();
            if (cl == null) {
                it.remove();
                continue;
            }
            try {
                unregisterClassLoader(cl);
                count++;
            } catch (Exception ignored) {
            }
        }
        return count;
    }
    /**
     * 精确清理指定的 类加载 实例（从全局注册表移除并释放资源）。
     *
     * <p>与 {@link #unregisterClassLoader(ClassLoader)} 功能相同，但语义更明确：
     * 强调「精确清理单个 类加载」而非「注销」。适用于热重载场景中
     * 主动清理旧 类加载 的场景。</p>
     *
     * @param classLoader 待清理的 类加载，空 时不处理
     * @return true 表示成功清理，false 表示 类加载 不在注册表中或为 空
     */
    public static boolean clearClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return false;
        }
        boolean found = REGISTERED_CLASS_LOADERS.removeIf(ref -> {
            ClassLoader cl = ref.get();
            return cl != null && cl == classLoader;
        });
        if (!found) {
            return false;
        }
        try {
            if (classLoader instanceof AutoCloseable) {
                ((AutoCloseable) classLoader).close();
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            if (classLoader instanceof URLClassLoader) {
                ((URLClassLoader) classLoader).close();
            }
        } catch (Exception ignored) {
        }
        return true;
    }
    /**
     * 清理所有满足条件的已注册 类加载。
     *
     * @param predicate 过滤条件，返回 true 表示需要清理；空 时等同于 {@link #clearAllClassLoaders()}
     * @return 实际清理的 类加载 数量
     */
    public static int clearClassLoaders(Predicate<ClassLoader> predicate) {
        if (predicate == null) {
            return clearAllClassLoaders();
        }
        int count = 0;
        java.util.Iterator<java.lang.ref.WeakReference<ClassLoader>> it = REGISTERED_CLASS_LOADERS.iterator();
        while (it.hasNext()) {
            java.lang.ref.WeakReference<ClassLoader> ref = it.next();
            ClassLoader cl = ref.get();
            if (cl == null) {
                it.remove();
                continue;
            }
            if (predicate.test(cl)) {
                try {
                    unregisterClassLoader(cl);
                    count++;
                } catch (Exception ignored) {
                }
            }
        }
        return count;
    }
    /**
     * 判断指定的 类加载 是否已注册到全局弱引用集合中。
     *
     * @param classLoader 待检查的 类加载，空 时返回 false
     * @return true 表示该 类加载 已注册
     */
    public static boolean isRegisteredClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return false;
        }
        java.util.Iterator<java.lang.ref.WeakReference<ClassLoader>> it = REGISTERED_CLASS_LOADERS.iterator();
        while (it.hasNext()) {
            java.lang.ref.WeakReference<ClassLoader> ref = it.next();
            ClassLoader cl = ref.get();
            if (cl == null) {
                it.remove();
                continue;
            }
            if (cl == classLoader) {
                return true;
            }
        }
        return false;
    }
    /**
     * 获取当前已注册的存活 类加载 数量。
     *
     * @return 当前已注册的存活 类加载 数量
     */
    public static int getRegisteredClassLoaderCount() {
        int count = 0;
        java.util.Iterator<java.lang.ref.WeakReference<ClassLoader>> it = REGISTERED_CLASS_LOADERS.iterator();
        while (it.hasNext()) {
            java.lang.ref.WeakReference<ClassLoader> ref = it.next();
            ClassLoader cl = ref.get();
            if (cl == null) {
                it.remove();
                continue;
            }
            count++;
        }
        return count;
    }
    /**
     * 当指定类名存在并可解析时，执行回调函数。
     *
     * @param className 待检查的类名
     * @param consumer 解析成功后的回调，接收 {@link Class} 对象
     * @since 1.0
     */
    public static void ifPresent(String className, Consumer<Class<?>> consumer) {
        if (ClassUtils.isPresent(className)) {
            consumer.accept(ClassUtils.forName(className));
        }
    }
    /**
     * 检查指定类名是否存在，但不执行任何额外处理。
     *
     * @param className 待检查的类名
     * @since 1.0
     */
    public static void ifPresent(String className) {
        if (ClassUtils.isPresent(className)) {
        }
    }
    /**
     *                                                                                     .
     *
     * @param className                   
     * @param type                                                                   
     * @param consumer                                                             
     * @param <T>                            
     * @since 1.0
     * @return 是否present的结果
     */
    public static <T> void isPresent(final String className, Class<T> type, Consumer<T> consumer) {
        if (!isPresent(className)) { return; }
        Class<?> aClass = forName(className);
        if (null == aClass || !type.isAssignableFrom(aClass)) { return; }
        consumer.accept((T) forObject(aClass));
    }
    /**
     * 判断给定的 {@link Class} 对象是否非空（非 {@code null}）。
     *
     * @param clazz 待检查的类对象
     * @return 如果 {@code clazz} 非 {@code null} 则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isPresent(Class<?> clazz) {
        return clazz != null;
    }
    /**
     * 判断给定的 {@link Class} 对象是否为空（为 {@code null}）。
     *
     * @param clazz 待检查的类对象
     * @return 如果 {@code clazz} 为 {@code null} 则返回 {@code true}
     * @since 4.0.0.43
     */
    public static boolean isEmpty(Class<?> clazz) {
        return clazz == null;
    }
    /**
     * 判断指定类名是否存在于当前默认类加载器可见范围内。
     *
     * @param className 待判断的类名
     * @return 如果类可被解析则返回 {@code true}，否则返回 {@code false}
     * @since 1.0
     */
    public static boolean isPresent(final String className) {
        if (StringUtils.isEmpty(className)) {
            return false;
        }
        return isPresent(className, getDefaultClassLoader());
    }
    /**
     *                                                                            .
     *
     *                                                                         
     *
     * @param className                   
     * @param classLoader                               
     * @return                          true                false
     * @since 1.0
     */
    public static boolean isPresent(final String className, final ClassLoader classLoader) {
        return MapUtils.computeIfAbsent(CACHE, className, it -> {
            try {
                final Class<?> aClass = forName(className, classLoader);
                return null != aClass;
            } catch (Throwable ex) {
                return false;
            }
        });
    }
    /**
     * 类       .
     *
     *                               
     * -                      com.example.用户
     * -                Java.lang.字符串[]
     * - JVM                      [Ljava.lang.字符串;
     * -                int, 布尔值
     *
     * @param name                             空
     * @return              Class                                                  空
     * @since 1.0
     */
    public static Class<?> forName(String name) {
        if (null == name) { return null; }
        return forName(name, getDefaultClassLoader());
    }
    /**
     * 类                                           .
     *
     *                                                                                              
     *                                                                                        
     *
     * @param name                   
     * @param classLoader                               
     * @return              Class       
     * @throws ClassNotFoundException                                  
     * @throws RuntimeException     类notfound异常
     * @since 1.0
     */
    public static Class<?> toClassConfident(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, true, classLoader); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体
            } catch (ClassNotFoundException ex) {
                try {
                    throw new ClassNotFoundException("                  class                               class                   ", e);
                } catch (ClassNotFoundException exc) {
                    throw new RuntimeException(exc);
                }
            }
        }
    }
    /**
     * 使用默认类加载器解析类名并返回强制解析后的 {@link Class} 对象。
     *
     * @param name 待解析的类名
     * @return 解析得到的 {@link Class} 对象
     * @throws RuntimeException 如果解析失败，则抛出包装后的运行时异常
     * @since 1.0
     */
    public static Class<?> toClassConfident(String name) {
        return toClassConfident(name, getDefaultClassLoader());
    }
    /**
     * 解析类名并校验其是否兼容指定的返回类型。
     *
     * @param name 待解析的类名
     * @param returnType 期望的返回类型
     * @param <T> 泛型类型参数
     * @return 如果解析成功且类型兼容，则返回对应 {@link Class}，否则返回 {@code null}
     * @since 1.0
     */
    public static <T> Class<T> forName(String name, Class<T> returnType) {
        return ReflectUtils.forName(name, returnType);
    }
    /**
     * 类       .
     *
     *                               
     * -                      com.example.用户
     * -                Java.lang.字符串[]   [[Ljava.lang.字符串;
     * -                int   布尔值
     * -             Java.util.映射.Entry                      Java.util.映射$Entry
     *
     * @param name                   
     * @param classLoader                                         空
     * @return              Class                                                  空
     * @since 1.0
     */
    public static Class<?> forName(String name, ClassLoader classLoader) {
        if (null == name) { return null; }
        //                
        Class<?> clazz = CLASS_NAME_CACHE.get(name);
        if (clazz != null) {
            return clazz;
        }
        //                   
        clazz = resolvePrimitiveClassName(name);
        if (clazz != null) {
            CLASS_NAME_CACHE.put(name, clazz);
            return clazz;
        }
        //                      
        clazz = COMMON_CLASS_CACHE.get(name);
        if (clazz != null) {
            CLASS_NAME_CACHE.put(name, clazz);
            return clazz;
        }
        // "java.lang.String[]" style arrays
        if (name.endsWith(ARRAY_SUFFIX)) {
            String elementClassName = name.substring(0, name.length() - ARRAY_SUFFIX.length());
            Class<?> elementClass = forName(elementClassName, classLoader);
            if (elementClass != null) {
                clazz = Array.newInstance(elementClass, 0).getClass();
                CLASS_NAME_CACHE.put(name, clazz);
                return clazz;
            }
        }
        // "[Ljava.lang.String;" style arrays
        if (name.startsWith(NON_PRIMITIVE_ARRAY_PREFIX) && name.endsWith(SYMBOL_SEMICOLON)) {
            String elementName = name.substring(NON_PRIMITIVE_ARRAY_PREFIX.length(), name.length() - 1);
            Class<?> elementClass = forName(elementName, classLoader);
            if (elementClass != null) {
                clazz = Array.newInstance(elementClass, 0).getClass();
                CLASS_NAME_CACHE.put(name, clazz);
                return clazz;
            }
        }
        // "[[I" or "[[Ljava.lang.String;" style arrays
        if (name.startsWith(INTERNAL_ARRAY_PREFIX)) {
            String elementName = name.substring(INTERNAL_ARRAY_PREFIX.length());
            Class<?> elementClass = forName(elementName, classLoader);
            if (elementClass != null) {
                clazz = Array.newInstance(elementClass, 0).getClass();
                CLASS_NAME_CACHE.put(name, clazz);
                return clazz;
            }
        }
        ClassLoader clToUse = classLoader;
        if (clToUse == null) {
            clToUse = getDefaultClassLoader();
        }
        try {
 // 底层统一走 reflect工具.for名称（带缓存 + 调试日志）
            clazz = ReflectUtils.forName(name, clToUse);
            if (clazz != null) {
                CLASS_NAME_CACHE.put(name, clazz);
                return clazz;
            }
        } catch (Exception ex) {
            // 忽略，继续尝试嵌套类解析
        }
        {
            int lastDotIndex = name.lastIndexOf(PACKAGE_SEPARATOR);
            if (lastDotIndex != -1) {
                String nestedClassName =
                        name.substring(0, lastDotIndex) + NESTED_CLASS_SEPARATOR + name.substring(lastDotIndex + 1);
                try {
                    clazz = ReflectUtils.forName(nestedClassName, clToUse);
                    if (clazz != null) {
                        CLASS_NAME_CACHE.put(name, clazz);
                        return clazz;
                    }
                } catch (Exception ex2) {
 // 忽略，返回 空
                }
            }
            return null;
        }
    }
    /**
     * 安全版本 {@link #forName(String)}，类不存在时返回 {@link Void#CLASS} 而非 {@code null}。
     *
     * @param name 类名
     * @return 解析到的 {@link Class}，找不到时返回 {@code void.class}
     * @since 4.0.0.43
     */
    public static Class<?> forNameSafe(String name) {
        if (null == name) { return void.class; }
        Class<?> clazz = forName(name);
        return clazz != null ? clazz : void.class;
    }
    /**
     * 安全版本 {@link #forName(String, ClassLoader)}，类不存在时返回 {@link Void#CLASS} 而非 {@code null}。
     *
     * @param name        类名
     * @param classLoader 类加载器
     * @return 解析到的 {@link Class}，找不到时返回 {@code void.class}
     * @since 4.0.0.43
     */
    public static Class<?> forNameSafe(String name, ClassLoader classLoader) {
        if (null == name) { return void.class; }
        Class<?> clazz = forName(name, classLoader);
        return clazz != null ? clazz : void.class;
    }
    /**
     * 解析类名并校验其是否兼容指定的返回类型，安全版本不返回 {@code null}。
     *
     * @param name       待解析的类名
     * @param returnType 期望的返回类型
     * @param <T>        泛型类型参数
     * @return 如果解析成功且类型兼容，则返回对应 {@link Class}，否则返回 {@link Void#CLASS}
     * @since 4.0.0.43
     */
    public static <T> Class<T> forNameSafe(String name, Class<T> returnType) {
        Class<?> clazz = forName(name);
        if (clazz == null || !returnType.isAssignableFrom(clazz)) {
            return (Class<T>) void.class;
        }
        return (Class<T>) clazz;
    }
    /**
     * 根据基本类型名称解析对应的 {@link Class} 对象。
     *
     * @param name 基本类型名称，如 {@code int}、{@code boolean} 等
     * @return 如果名称对应基本类型则返回对应 {@link Class}，否则返回 {@code null}
     * @since 1.0
     */
    public static Class<?> resolvePrimitiveClassName(String name) {
        Class<?> result = null;
        if (name != null && name.length() <= 7) {
            result = PRIMITIVE_TYPE_NAME_MAP.get(name);
        }
        return result;
    }
    /**
     *                                     .
     *
     * @param name                   
     * @param params                                                 
     * @param <T>                               
     * @return                                                     null
     * @since 1.0
     */
    public static <T> T forObject(String name, Object... params) {
        try {
            return (T) forObject(forName(name), getDefaultClassLoader(), params);
        } catch (Exception e) {
            return null;
        }
    }
    /**
     *                                                                   .
     *
     * @param typeName                   
     * @param type                                                                
     * @param params                                                 
     * @param <T>                               
     * @return                                                                             null
     * @since 1.0
     */
    public static <T> T forObjectWithType(String typeName, Class<T> type, Object... params) {
        try {
            T forObject = (T) forObject(forName(typeName, type.getClassLoader()), params);
            if (null == forObject || !type.isAssignableFrom(forObject.getClass())) {
                return null;
            }
            return forObject;
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 根据类对象实例化对象，优先使用默认构造器或匹配参数的构造器。
     *
     * @param type 目标类型
     * @param params 构造参数
     * @param <T> 返回类型泛型
     * @return 创建成功的对象，失败时返回 {@code null}
     * @since 1.0
     */
    public static <T> T forObject(Class<T> type, Object... params) {
        try {
            return forObject(type, getDefaultClassLoader(), params);
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 类                                              .
     *
     *                         
     * 1.                       列表   集合     设置
     * 2.                                                                            
     * 3.                                                                         
     * 4.                                                                                  
     *
     *       /                     
     * 1.                          空
     *
     * @param tClass                   
     * @param classLoader                               
     * @param params                                                 
     * @param <T>                               
     * @return                                                     null
     * @throws Exception                                  
     * @since 1.0
     */
    public static <T> T forObject(Class<T> tClass, ClassLoader classLoader, Object... params) throws Exception {
        if (null == tClass) { return null; }
        if (List.class.isAssignableFrom(tClass) || Collection.class.isAssignableFrom(tClass)) {
            return (T) new ArrayList<>(16);
        }
        if (Set.class.isAssignableFrom(tClass)) {
            return (T) new HashSet<>(16);
        }
        if (tClass.isInterface()) {
            return null;
        }
        if (null == params || params.length == 0) {
            T newInstance = ClassUtils.newInstance(tClass);
            if (null != newInstance) {
                return newInstance;
            }
        }
        Class<?>[] classes = ClassUtils.toType(params);
        Constructor<T> declaredConstructor = ClassUtils.getConstructor(tClass, classes);
        if (null != declaredConstructor) {
            setAccessible(declaredConstructor);
            try {
                params = createArgs(params, declaredConstructor);
                return declaredConstructor.newInstance(params); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（forObject 实例化封装）
            } catch (Exception ignore) {
            }
        }
        return (T) createAlgorithm(tClass, params);
    }
    /**
     * 根据构造器参数类型生成可用于反射调用的实际参数数组。
     *
     * @param params 原始参数数组
     * @param declaredConstructor 目标构造器
     * @param <T> 构造器声明类型
     * @return 转换后的参数数组
     * @since 1.0
     */
    private static <T> Object[] createArgs(Object[] params, Constructor<T> declaredConstructor) {
        return createArgs(params, declaredConstructor.getParameterTypes());
    }
    /**
     * 按目标参数类型对输入参数做必要的转换，确保可传给反射调用。
     *
     * @param params 原始参数数组
     * @param parameterTypes 目标方法或构造器参数类型
     * @return 转换后的参数数组
     * @since 1.0
     */
    public static Object[] createArgs(Object[] params, Class<?>[] parameterTypes) {
        Object[] rs = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            Object param = params[i];
            rs[i] = param;
            if (null == param) {
                continue;
            }
            if (parameterType.isAssignableFrom(param.getClass())) {
                continue;
            }
            if (parameterType.getTypeName().equals(param.getClass().getTypeName())) {
                continue;
            }
            rs[i] = Converter.convertIfNecessary(param, parameterType);
        }
        return rs;
    }
    /**
     *             
     *
     * @param params       
     * @return       
     * @param tClass t类
     *
     * <p>豁免说明：本类为反射基础设施，按构造器匹配创建实例是工具类核心职责，
     * 允许在内部实现中直接使用 java.lang.reflect API。</p>
     */
    private static <T> T createAlgorithm(Class<T> tClass, Object[] params) {
        Map<Constructor<?>, Object[]> loss = new LinkedHashMap<>(8);
        Map<Constructor<?>, Object[]> allnull = new LinkedHashMap<>(8);
        Map<Class<?>, Object> typeAndValue = createTypeAndValue(params);
        Constructor<?>[] declaredConstructors = tClass.getDeclaredConstructors(); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（构造器匹配创建实例）
        for (Constructor<?> declaredConstructor : declaredConstructors) {
            T algorithm = null;
            try {
                algorithm = createAlgorithm(declaredConstructor, typeAndValue, loss, allnull);
            } catch (Exception ignored) {
            }
            if (null != algorithm) {
                return algorithm;
            }
        }
        if (!loss.isEmpty()) {
            for (Map.Entry<Constructor<?>, Object[]> entry : loss.entrySet()) {
                Constructor<?> constructor = entry.getKey();
                setAccessible(constructor);
                try {
                    Object newInstance = constructor.newInstance(entry.getValue()); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（构造器兜底实例化）
                    if (null != newInstance) {
                        return (T) newInstance;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (!allnull.isEmpty()) {
            for (Map.Entry<Constructor<?>, Object[]> entry : allnull.entrySet()) {
                Constructor<?> constructor = entry.getKey();
                setAccessible(constructor);
                try {
                    Object newInstance = constructor.newInstance(entry.getValue()); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（全空参构造器兜底实例化）
                    if (null != newInstance) {
                        return (T) newInstance;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
    /**
     * 尝试用给定构造器和参数集合创建实例，并记录无法完全匹配的构造器。
     *
     * @param declaredConstructor 待尝试的构造器
     * @param params 参数类型与参数值映射
     * @param loss 记录参数不完整但可尝试匹配的构造器
     * @param allNull 记录参数全为空的构造器
     * @param <T> 返回类型泛型
     * @return 创建成功的实例，失败时返回 {@code null}
     */
    private static <T> T createAlgorithm(Constructor<?> declaredConstructor, Map<Class<?>, Object> params, Map<Constructor<?>, Object[]> loss,
                                         Map<Constructor<?>, Object[]> allNull) throws Exception {
        Class<?>[] parameterTypes = declaredConstructor.getParameterTypes();
        if (parameterTypes.length == 0) {
            setAccessible(declaredConstructor);
            return (T) ReflectUtils.instantiate(declaredConstructor.getDeclaringClass());
        }
        Object[] args = getArgs(parameterTypes, params);
        if (isAllNull(args)) {
            allNull.put(declaredConstructor, args);
            return null;
        }
        if (hasNone(args)) {
            setAccessible(declaredConstructor);
            return (T) declaredConstructor.newInstance(args); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（参数可转换时的构造器实例化）
        }
        loss.put(declaredConstructor, args);
        return null;
    }
    /**
     *                   
     *
     * @param collection       
     * @return class
     * @throws NullPointerException ex
     */
    public static Class<?>[] toType(Object[] collection) {
        if (null == collection || collection.length == 0) { return new Class[0]; }
        Class<?>[] rs = new Class<?>[collection.length];
        int index = 0;
        for (Object o : collection) {
            rs[index++] = toType(o);
        }
        return rs;
    }
    /**
     * 根据对象实例推断其对应的 {@link Class} 类型，兼容代理类与字符串类型名。
     *
     * @param object 待分析的对象
     * @return 对应的 {@link Class} 类型
     */
    public static Class<?> toType(Object object) {
        if (object == null) {
            return Void.class;
        }
        if (object instanceof Class<?>) {
            return (Class<?>) object;
        }
        if (object instanceof String && ((String) object).contains(SYMBOL_DOT)) {
            Class<?> aClass = ClassUtils.forName(object.toString());
            if (null != aClass && void.class != aClass) {
                return ObjectUtils.defaultIfNull(aClass, void.class);
            }
        }
        Class<?> aClass = object.getClass();
        if (!Proxy.isProxyClass(aClass)) {
            String typeName = aClass.getTypeName();
            if (!typeName.contains("$$EnhancerBySpringCGLIB$$")) {
                return aClass;
            }
            String realTypeName = typeName.substring(0, typeName.indexOf("$$"));
            Class<?> aClass1 = forName(realTypeName, object.getClass().getClassLoader());
            return null == aClass1 ? void.class : ObjectUtils.defaultIfNull(aClass1, void.class);
        }
        String toString = object.toString();
        if (!toString.contains("$Proxy")) {
            Class<?> aClass1 = forName(StringUtils.removeSuffixContains(toString.replace("@", ""), "("), object.getClass().getClassLoader());
            if (null == aClass1) {
                aClass1 = forName(toString.substring(0, toString.indexOf("@")), object.getClass().getClassLoader());
            }
            return null == aClass1 ? void.class : ObjectUtils.defaultIfNull(aClass1, void.class);
        }
        return void.class;
    }
    /**
     * 将类对象或类名输入转换为可直接实例化的对象表示，便于后续构造逻辑使用。
     *
     * @param object 输入对象，支持 {@link Class} 或类名字符串
     * @return 适配后的对象，若无法处理则返回原始对象
     */
    public static Object asObject(Object object) {
        if (object instanceof Class) {
            Class<?> type = fromPrimitive((Class<?>) object);
            return ClassUtils.forObject(type);
        }
        if (object instanceof String) {
            return ClassUtils.forObject(object.toString());
        }
        return object;
    }
    /**
     * 将原始类型转换为对应的包装类型，若输入本身不为原始类型则直接返回原值。
     *
     * @param target 待转换的类型对象
     * @param <T> 类型参数
     * @return 对应的包装类型或原始类型
     * @see Boolean#TYPE
     * @see Character#TYPE
     * @see Byte#TYPE
     * @see Short#TYPE
     * @see Integer#TYPE
     * @see Long#TYPE
     * @see Float#TYPE
     * @see Double#TYPE
     * @see Void#TYPE
     */
    public static <T> Class<T> fromPrimitive(Class<T> target) {
        if (target.isPrimitive()) {
            if (PRIMITIVE_PACK.containsKey(target)) {
                return (Class<T>) PRIMITIVE_PACK.get(target);
            }
        }
        String name = target.getName();
        if (name.startsWith(SYMBOL_LEFT_SQUARE_BRACKET)) {
            String all = name.replaceAll("\\[", "");
            if (BASIC_VIRTUAL.containsRow(all)) {
                Map<Class<?>, Class<?>> immutableMap = BASIC_VIRTUAL.row(all);
                return (Class<T>) ClassUtils.forName(name.replace(all, immutableMap.values().iterator().next().getName()));
            }
        }
        return target;
    }
    /**
     *                   
     *
     * @param target    
     * @param <T>          
     * @return          
     * @see Boolean#TYPE
     * @see Character#TYPE
     * @see Byte#TYPE
     * @see Short#TYPE
     * @see Integer#TYPE
     * @see Long#TYPE
     * @see Float#TYPE
     * @see Double#TYPE
     * @see Void#TYPE
     */
    public static boolean isPrimitive(Class<?> target) {
        return target.isPrimitive();
    }
    /**
     *             
     *
     * @param annotations       
     * @return       
     */
    public static String[] toTypeName(Annotation[] annotations) {
        if (null == annotations || annotations.length == 0) { return SYMBOL_EMPTY_STRING_ARRAY; }
        String[] result = new String[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            Annotation parameterType = annotations[i];
            result[i] = parameterType.annotationType().getTypeName();
        }
        return result;
    }
    /**
     *             
     *
     * @param parameterTypes       
     * @return       
     */
    public static String[] toTypeName(Class<?>[] parameterTypes) {
        if (null == parameterTypes || parameterTypes.length == 0) { return SYMBOL_EMPTY_STRING_ARRAY; }
        String[] result = new String[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            result[i] = parameterType.getTypeName();
        }
        return result;
    }
    /**
     * <p>1.                     </p>
     * <p>2.                  </p>
     *
     * @param value    
     * @return             
     * @param includes includes
     */
    public static Type[] getActualTypeArguments(final Class<?> value, final Class<?>... includes) {
        return ACTUAL.computeIfAbsent(value, it -> {
            Type type = value.getGenericSuperclass();
            List<Type> types = new ArrayList<>();
            if (type instanceof ParameterizedType && container(type, includes)) {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                types.addAll(Arrays.asList(actualTypeArguments));
            }
            Type[] genericInterfaces = value.getGenericInterfaces();
            for (Type anInterface : genericInterfaces) {
                if (anInterface instanceof ParameterizedType && container(anInterface, includes)) {
                    ParameterizedType parameterizedType = (ParameterizedType) anInterface;
                    Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                    for (Type actualTypeArgument : actualTypeArguments) {
                        if (actualTypeArgument instanceof ParameterizedType) {
                            types.add(((ParameterizedType) actualTypeArgument).getRawType());
                        } else {
                            types.add(actualTypeArgument);
                        }
                    }
                }
            }
            return types.toArray(new Type[0]);
        });
    }
    /**
     *                
     *
     * @param type        
     * @param includes          
     * @return       true
     */
    private static boolean container(Type type, Class<?>[] includes) {
        if (null == type || null == includes || includes.length == 0) {
            return true;
        }
        String typeName = type.getTypeName();
        for (Class<?> include : includes) {
            int index = typeName.indexOf("<");
            if (index != -1) {
                typeName = typeName.substring(0, index);
            }
            if (typeName.equals(include.getName())) {
                return true;
            }
        }
        return false;
    }
    /**
     * 类路径                                                <br>
     * 类路径
     *
     * @return ClassPath
     */
    public static String getClassPath() {
        return getClassPath(false);
    }
    /**
     * 类路径         类路径
     *
     * @param isEncoded                               
     * @return ClassPath
     * @since 3.2.1
     */
    public static String getClassPath(boolean isEncoded) {
        final URL url1 = getClassPathUrl();
        String url = isEncoded ? url1.getPath() : UrlUtils.getDecodedPath(url1);
        return FileUtils.normalize(url);
    }
    /**
     * 类路径 URL
     *
     * @return ClassPath URL
     */
    public static URL getClassPathUrl() {
        return getResourceUrl(SYMBOL_EMPTY);
    }
    /**
     *                URL<br>
     *          /               :
     *
     * <pre>
     * config/a/db.config
     * spring/xml/test.xml
     * </pre>
     *
     * @param source                类路径
     * @return       URL
     */
    public static URL getResourceUrl(String source) {
        if (StringUtils.isBlank(source)) {
            return null;
        }
        File temp = new File(source);
        try {
            if (temp.exists()) {
                return temp.toURI().toURL();
            }
        } catch (MalformedURLException ignored) {
        }
        return ClassUtils.class.getClassLoader().getResource(source);
    }
    /**
     * Java
     *
     * @param type       
     * @return java   
     */
    public static boolean isJavaType(Class<?> type) {
        return type.getTypeName().startsWith("java.") ||
                type.isPrimitive()
                ;
    }
    /**
     * spring   
     *
     * @param type       
     * @return java   
     */
    public static boolean isSpringType(Class<?> type) {
        return type.getTypeName().startsWith("org.springframework.") ||
                type.isPrimitive()
                ;
    }
    /**
     *                                           .
     *
     *                                                                                                          
     *
     * @param aClass                
     * @param callback                                           
     * @since 1.0
     */
    public static void doWithFields(final Class<?> aClass, final Consumer<Field> callback) {
        try {
            for (Field field : getFields(aClass)) {
                try {
                    callback.accept(field);
                } catch (Throwable ex) {
                    throw new IllegalStateException("Not allowed to access field '" + field.getName() + "': " + ex);
                }
            }
        } catch (Throwable ignore) {
        }
    }
    /**
     *                                     .
     *
     *                                                                   
     *
     * @param obj       
     * @param fieldName             
     * @return           Field                                   空
     * @since 1.0
     */
    public static Field getFields(final Object obj, String fieldName) {
        if (null == obj) { return null; }
        for (Field field : getFields(obj)) {
            if (fieldName.equals(field.getName())) {
                return field;
            }
        }
        return null;
    }
    /**
     *                                                                .
     *
     * 对象
     *
     * @param obj       
     * @return                 List                   空              列表
     * @since 1.0
     */
    public static List<Field> getFields(final Object obj) {
        if (null == obj) { return Collections.emptyList(); }
        final Class<?> aClass = ClassUtils.toType(obj);
        return MapUtils.getComputeIfFunction(CLASS_FIELD_LOCAL, aClass, aClass12 -> {
            List<Field> result = new ArrayList<>();
            Class<?> newClass = aClass12;
            while (!ClassUtils.isObject(newClass)) {
                Field[] fields = new Field[0];
                try {
                    fields = newClass.getDeclaredFields();
                    result.addAll(Arrays.asList(fields));
                    newClass = newClass.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
            return result;
        });
    }
    /**
     *                                                             .
     *
     *                                                                
     *
     * @param aClass                
     * @param callback                                           
     * @since 1.0
     */
    public static void doWithLocalFields(final Class<?> aClass, final Consumer<Field> callback) {
        for (Field field : getLocalFields(aClass)) {
            try {
                callback.accept(field);
            } catch (Throwable ex) {
                throw new IllegalStateException("Not allowed to access field '" + field.getName() + "': " + ex);
            }
        }
    }
    /**
     *                                                             .
     *
     * @param obj       
     * @return                                   List
     * @since 1.0
     */
    public static List<Field> getLocalFields(final Object obj) {
        if (null == obj) { return Collections.emptyList(); }
        final Class<?> aClass = ClassUtils.toType(obj);
        return MapUtils.getComputeIfFunction(CLASS_FIELD, aClass, it -> {
            Field[] fields = aClass.getDeclaredFields();
            return Arrays.asList(fields);
        });
    }
    /**
     *                                                                   .
     *
     * @param aClass                
     * @return                 List                               列表
     * @since 1.0
     */
    public static List<Field> getFields(final Class<?> aClass) {
        return MapUtils.getComputeIfFunction(CLASS_FIELD_LOCAL, aClass, aClass12 -> {
            List<Field> result = new ArrayList<>();
            Class<?> newClass = aClass12;
            while (!ClassUtils.isObject(newClass)) {
                Field[] fields = new Field[0];
                try {
                    fields = newClass.getDeclaredFields();
                    result.addAll(Arrays.asList(fields));
                    newClass = newClass.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
            return result;
        });
    }
    /**
     *                                           .
     *
     * 对象
     *
     * @param type                
     * @param name             
     * @return           Field                                   空
     * @since 1.0
     */
    public static Field findField(Class<?> type, String name) {
        List<Field> fields = getFields(type);
        for (Field field : fields) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }
    /**
     *             
     *
     * @param aClass      
     * @param callback             
     */
    public static void doWithLocalMethods(final Class<?> aClass, final Consumer<Method> callback) {
        if (null == aClass || null == callback) {
            return;
        }
        for (Method method : getLocalMethods(aClass)) {
            try {
                callback.accept(method);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Not allowed to access method '" + method.getName() + "': " + throwable);
            }
        }
    }
    /**
     *             
     *
     * @param aClass          
     * @param callback                 
     * @param methodFilter          
     */
    public static void doWithMethods(final Class<?> aClass, final Consumer<Method> callback, MethodFilter methodFilter) {
        if (null == aClass || null == callback) {
            return;
        }
        Set<Method> cache = new HashSet<>(64);
        for (Method method : getMethods(aClass)) {
            if (methodFilter != null && !methodFilter.matches(method)) {
                continue;
            }
            if(cache.contains(method)) {
                return;
            }
            cache.add(method);
            try {
                callback.accept(method);
            } catch (Throwable throwable) {
                log.warn("Not allowed to access method '" + method.getName() + "': " + throwable);
            }
        }
    }
    /**
     *             
     *
     * @param aClass      
     * @param callback             
     */
    public static void doWithMethods(final Class<?> aClass, final Consumer<Method> callback) {
        doWithMethods(aClass, callback, null);
    }
    /**
     *                   
     *
     * @param obj       
     * @return             
     */
    public static List<Method> getMethods(final Object obj) {
        if (null == obj) {
            return Collections.emptyList();
        }
        final Class<?> aClass = ClassUtils.toType(obj);
        return MapUtils.getComputeIfFunction(CLASS_METHOD, aClass, it -> {
            List<Method> result = new ArrayList<>();
            Class<?> newClass = aClass;
            while (!ClassUtils.isObject(newClass)) {
                Method[] methods = null;
                try {
                    methods = newClass.getDeclaredMethods();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
                result.addAll(Arrays.asList(methods));
                newClass = newClass.getSuperclass();
            }
            Class<?>[] interfaces = aClass.getInterfaces();
            Set<Class<?>> allInterfaces = new HashSet<>(16);
            for (Class<?> anInterface : interfaces) {
                Class<?>[] newInterface1 = anInterface.getInterfaces();
                allInterfaces.add(anInterface);
                allInterfaces.addAll(Arrays.asList(newInterface1));
            }
            for (Class<?> allInterface : allInterfaces) {
                Method[] methods = allInterface.getDeclaredMethods();
                for (Method method : methods) {
                    if (method.isDefault()) {
                        result.add(method);
                    }
                }
            }
            return result;
        });
    }
    /**
     *                   
     *
     * @param obj       
     * @return             
     */
    public static List<Method> getLocalMethods(final Object obj) {
        if (null == obj) {
            return Collections.emptyList();
        }
        final Class<?> aClass = ClassUtils.toType(obj);
        return MapUtils.getComputeIfFunction(CLASS_METHOD_LOCAL, aClass, it -> {
            Method[] methods = aClass.getDeclaredMethods();
            return Arrays.asList(methods);
        });
    }
    /**
     *       
     *
     * @param tClass        
     * @param classes       
     * @param <T>           
     * @return       
     *
     * <p>豁免说明：本类为反射基础设施，按参数可赋值性挑选构造器是工具类核心职责，
     * 允许在内部实现中直接使用 java.lang.reflect API。</p>
     */
    public static <T> Constructor<T> getConstructor(Class<T> tClass, Class<?>[] classes) {
        if (null == tClass || null == classes) {
            return null;
        }
        Constructor<?>[] declaredConstructors = tClass.getDeclaredConstructors(); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（getConstructor 构造器匹配）
        Constructor<?> item = null;
        root:
        for (Constructor<?> declaredConstructor : declaredConstructors) {
            Class<?>[] parameterTypes = declaredConstructor.getParameterTypes();
            if (parameterTypes.length != classes.length) {
                continue;
            }
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                Class<?> aClass = classes[i];
                boolean assignable = aClass == parameterType
                        || parameterType.isAssignableFrom(aClass)
                        || Void.class.isAssignableFrom(aClass)
                        || void.class.isAssignableFrom(aClass);
                if (assignable) {
                    if (i == parameterTypes.length - 1) {
                        item = declaredConstructor;
                        break root;
                    }
                    continue;
                }
                break;
            }
        }
        return (Constructor<T>) item;
    }
    /**
     * 对象.类
     *
     * @param clazz    
     * @return        Object.class       空       true
     */
    public static boolean isObject(Class<?> clazz) {
        return null == clazz || Object.class.getName().equals(clazz.getName());
    }
    /**
     *             
     *
     * @param args       
     * @return             
     */
    private static boolean hasNone(Object[] args) {
        for (Object arg : args) {
            if (null == arg) {
                return false;
            }
        }
        return true;
    }
    /**
     *             
     *
     * @param args       
     * @return             
     */
    private static boolean isAllNull(Object[] args) {
        for (Object arg : args) {
            if (null != arg) {
                return false;
            }
        }
        return true;
    }
    /**
     *             
     *
     * @param parameterTypes       
     * @param params               
     * @return       
     */
    private static Object[] getArgs(Class<?>[] parameterTypes, Map<Class<?>, Object> params) {
        Object[] rs = new Object[parameterTypes.length];
        int index = 0;
        for (Class<?> aClass : parameterTypes) {
            Object o = params.get(aClass);
            rs[index++] = createValue(o, aClass, params);
        }
        return rs;
    }
    /**
     *          
     *
     * @param o                        
     * @param aClass             
     * @param params             
     * @return    
     */
    private static Object createValue(Object o, Class<?> aClass, Map<Class<?>, Object> params) {
        if (null != o) {
            return o;
        }
        for (Map.Entry<Class<?>, Object> entry : params.entrySet()) {
            if (void.class == entry.getKey()) {
                continue;
            }
            if (aClass.isAssignableFrom(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (params.size() == 1) {
            Map.Entry<Class<?>, Object> first = MapUtils.getFirst(params);
            Object necessary = Converter.convertIfNecessary(first.getValue(), aClass);
            if (null != necessary) {
                return necessary;
            }
        }
        return null;
    }
    /**
     *             
     *
     * @param params    
     * @return             
     */
    private static Map<Class<?>, Object> createTypeAndValue(Object[] params) {
        Map<Class<?>, Object> rs = new HashMap<>(params.length);
        for (Object param : params) {
            if (null == param) {
                rs.put(void.class, null);
            } else {
                rs.put(param.getClass(), param);
            }
        }
        return rs;
    }
    /**
     *             
     *
     * @param object           
     * @param type             
     * @param methodName       
     * @param args             
     * @return       
     */
    public static Object invokeMethodChain(Object object, Class<?> type, String methodName, Object... args) {
        if (null == type) {
            return null;
        }
        Method method = findMethod(type, methodName, toType(args));
        if (null == method) {
            return null;
        }
        setAccessible(method);
        return invokeMethod(method, object, args);
    }
    /**
     *             
     *
     * @param bean                      
     * @param methodName                
     * @param methodParameterType             
     * @param args                      
     * @return       
     */
    public static Object invokeBean(Object bean, String methodName, Class<?>[] methodParameterType, Object... args) {
        if (null == bean) {
            return null;
        }
        Class<?> type = toType(bean);
        Method method = findMethod(type, methodName, methodParameterType);
        if (null == method) {
            return null;
        }
        setAccessible(method);
        return invokeMethod(method, bean, args);
    }
    /**
     *             
     *
     * @param bean             
     * @param methodName       
     * @param args             
     * @return       
     */
    public static Object invokeBean(Object bean, String methodName, Object... args) {
        if (null == bean) {
            return null;
        }
        Class<?> type = toType(bean);
        Method method = findMethod(type, methodName, toType(args));
        if (null == method) {
            return null;
        }
        setAccessible(method);
        return invokeMethod(method, bean, args);
    }
    /**
     * 在指定类型上按参数类型推断查找方法并调用（静态方法）。
     *
     * @param type       目标类型，为 null 时返回 null
     * @param methodName 方法名称
     * @param args       方法参数
     * @return 方法返回值，类型或方法不存在时返回 null
     */
    public static Object invokeMethod(Class<?> type, String methodName, Object... args) {
        if (null == type) {
            return null;
        }
        Method method = findMethod(type, methodName, toType(args));
        if (null == method) {
            return null;
        }
        return invokeMethod(method, null, args);
    }
    /**
     * 通过 {@link MethodHandle} 调用方法（支持 {@link Callable} 短路求值）。
     *
     * <p>若目标对象为 {@link Callable}，则直接调用其 {@code call()} 方法（忽略参数），
     * 否则绑定目标对象后执行 {@link MethodHandle#invokeWithArguments(Object[])}。</p>
     *
     * @param methodHandle 目标方法句柄，为 null 时返回 null
     * @param bean         目标对象
     * @param args         方法参数
     * @return 方法返回值，句柄为 null 或调用失败时返回 null
     */
    public static Object invokeMethod(MethodHandle methodHandle, Object bean, Object... args) {
        if (null == methodHandle) {
            return null;
        }
        // 目标对象为 Callable 时直接短路调用
        if (bean instanceof Callable) {
            try {
                return ((Callable<?>) bean).call();
            } catch (Exception ignored) {
            }
            return null;
        }
        return invoke(methodHandle, bean, args);
    }
    /**
     * 通过 {@link Method} 对象调用方法（支持 {@link MethodInvoker} 委托和 {@link MethodHandle} 回退）。
     *
     * <p>调用策略：若目标对象为 {@link Callable} 则短路调用；若方法为接口默认方法则走
     * {@link MethodHandles} 私有路径；其余委托 {@link MethodInvoker} 执行，失败时回退
     * {@link ClassUtils#invokeBean} 按名称重新查找调用。</p>
     *
     * @param method 目标方法对象，为 null 时返回 null
     * @param bean   目标对象
     * @param args   方法参数
     * @return 方法返回值，方法为 null 或调用失败时返回 null
     */
    public static Object invokeMethod(Method method, Object bean, Object... args) {
        if (null == method) {
            return null;
        }
        // 目标对象为 Callable 时直接短路调用
        if (bean instanceof Callable) {
            try {
                return ((Callable<?>) bean).call();
            } catch (Exception ignored) {
            }
            return null;
        }
        // 接口默认方法走 MethodHandles 私有路径
        if (method.isDefault()) {
            return invokeDefaultMethod(method, bean, args);
        }
        // 普通方法委托 MethodInvoker 执行
        try {
            return MethodInvoker.invoke(method, bean, args);
        } catch (RuntimeException e) {
            // 代理对象声明类不匹配时，按方法名回退到 invokeBean 重新查找
            if (e.getCause() != null &&
                "object is not an instance of declaring class".equalsIgnoreCase(e.getCause().getLocalizedMessage())) {
                return ClassUtils.invokeBean(bean, method.getName(), method.getParameterTypes(), args);
            }
            throw e;
        }
    }
    /**
     * 通过 {@link MethodHandle} 直接调用方法。
     *
     * @param methodHandle 目标方法句柄，不能为 null
     * @param obj          目标对象
     * @param args         方法参数
     * @return 方法返回值，句柄为 null 或调用异常时返回 null
     */
    public static Object invoke(MethodHandle methodHandle, Object obj, Object... args) {
        try {
            return methodHandle.bindTo(obj).invokeWithArguments(args);
        } catch (Throwable e) {
            return null;
        }
    }
    /**
     *             
     *
     * @param method       
     * @param bean         
     * @param args         
     * @return       
     */
    public static Object invokeDefaultMethod(Method method, Object bean, Object... args) {
        if (bean instanceof Callable) {
            try {
                return ((Callable<?>) bean).call();
            } catch (Exception ignored) {
            }
        }
        if (!method.isDefault()) {
            return null;
        }
        try {
            Constructor<?> constructor = null;
            try {
                constructor = MethodHandles.Lookup.class
                        .getDeclaredConstructor(Class.class, Class.class, int.class); // [P3C 1.10 豁免] JDK 内部 MethodHandles.Lookup 私有构造器 + ClassUtils 为反射基础设施本体
            } catch (NoSuchMethodException e) {
                constructor = MethodHandles.Lookup.class
                        .getDeclaredConstructor(Class.class, int.class); // [P3C 1.10 豁免] JDK 内部 MethodHandles.Lookup 私有构造器 + ClassUtils 为反射基础设施本体
            }
            setAccessible(constructor);
            Class<?> declaringClass = method.getDeclaringClass();
            int allModes = MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PACKAGE;
            return (constructor.getParameterCount() == 2 ? ((MethodHandles.Lookup) constructor.newInstance(declaringClass, allModes)) : // [P3C 1.10 豁免] JDK 内部 MethodHandles.Lookup 构造器调用 + ClassUtils 为反射基础设施本体
                    ((MethodHandles.Lookup) constructor.newInstance(declaringClass, declaringClass.getSuperclass(), allModes)))
                    .unreflectSpecial(method, declaringClass)
                    .bindTo(bean)
                    .invokeWithArguments(method.getParameterCount() == 0 ? SYMBOL_EMPTY_OBJECT_ARRAY : args);
        } catch (ClassCastException e1) {
            throw e1;
        } catch (Throwable e) {
            return null;
        }
    }
    /**
     * 在类中查找指定名称与参数类型的方法（结果缓存于 {@link #cacheMethod}）。
     *
     * <p>查找范围包括本类及继承链、接口方法。按方法名精确匹配，
     * 参数类型按可赋值性（含基本类型/包装类型互转）判定。</p>
     *
     * @param clazz      目标类，不能为 null
     * @param name       方法名称
     * @param paramTypes 参数类型数组，为 null 时仅按方法名匹配
     * @return 匹配的 Method 对象，未找到时返回 null
     * @since 1.0
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        return cacheMethod.computeIfAbsent(clazz.hashCode() + name.hashCode() + paramTypes.hashCode(), new Function<Integer, Method>() {
            @Override
            /**
             * 应用
             * @param integer integer
             */
            public Method apply(Integer integer) {
                Method[] allMethod = getAllMethod(clazz);
                for (Method method : allMethod) {
                    if (name.equals(method.getName()) && (paramTypes == null || hasSameParams(method, paramTypes))) {
                        return method;
                    }
                }
                return null;
            }
        });
    }
    /**
     * 在类中查找指定名称与参数类型的已声明方法（仅本类，不含继承）。
     *
     * @param clazz      目标类，不能为 null
     * @param name       方法名称
     * @param paramTypes 参数类型数组
     * @return 匹配的 Method 对象，未找到时返回 null
     * @since 1.0
     */
    public static Method findDeclaredMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（findDeclaredMethod 封装）
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
    /**
     * 获取-全部
     *
     * @param clazz clazz
     * @return {@link 方法[]}
     */
    private static Method[] getAllMethod(Class<?> clazz) {
        return DECLARED_METHODS_CACHE.computeIfAbsent(clazz, new Function<Class<?>, Method[]>() {
            @Override
            /**
             * 应用
             * @param aClass a类
             */
            public Method[] apply(Class<?> aClass) {
                List<Method> rs = new LinkedList<>();
                // 防止循环引用（递归注册时同一类型重复出现）
                List<Class<?>> validate = new LinkedList<>();
                doRegisterMethod(rs, aClass, validate);
                return rs.toArray(new Method[0]);
            }
            /**
             * 执行注册方法
             * @param rs R
             * @param aClass a类
             * @param validate 校验
             */
            private void doRegisterMethod(List<Method> rs, Class<?> aClass, List<Class<?>> validate) {
                if(validate.contains(aClass)) {
                    return;
                }
                validate.add(aClass);
                Class<?> searchType = clazz;
                while (searchType != null) {
                    Method[] methods = (searchType.isInterface() ? searchType.getMethods() :
                            getMethods(searchType).toArray(SYMBOL_EMPTY_METHOD_ARRAY));
                    rs.addAll(Arrays.asList(methods));
                    Class<?>[] interfaces = searchType.getInterfaces();
                    if (ArrayUtils.isNotEmpty(interfaces)) {
                        for (Class<?> anInterface : interfaces) {
                            doRegisterMethod(rs, anInterface, validate);
                        }
                    }
                    searchType = searchType.getSuperclass();
                }
            }
        });
    }
    /**
     * 判断两个方法的参数类型是否一致（基本类型按包装类型互转比较）。
     *
     * @param method     源方法
     * @param paramTypes 期望的参数类型数组
     * @return 参数数量与类型均匹配时返回 true
     */
    private static boolean hasSameParams(Method method, Class<?>[] paramTypes) {
        if (paramTypes.length != method.getParameterCount()) {
            return false;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if(parameterType.isPrimitive()) {
                parameterType = ClassUtils.primitiveToWrapper(parameterType);
            }
            if (!parameterType.isAssignableFrom(paramTypes[i])) {
                return false;
            }
        }
        return true;
    }
    /**
     * 设置反射对象（Method / Field / Constructor）的可访问性。
     *
     * @param object 目标反射对象，为 null 时返回 false
     * @return 实际执行了 setAccessible(true) 时返回 true，否则返回 false
     */
    public static boolean setAccessible(Object object) {
        try {
            ModuleLoader.exportAllToAll();
        } catch (Throwable t) {
 // graalvm NAT-镜像 / 模块化环境下模块导出不可用时静默忽略
        }
        if(null == object) {
            return false;
        }
        if(object instanceof Method && !((Method) object).isAccessible()) {
            //        return AccessController.doPrivileged(new SetAccessibleAction<>(object));
            ((Method) object).setAccessible(true); // [P3C 1.10 豁免] ClassUtils.setAccessible 本体（反射基础设施）
            return true;
        }
        if(object instanceof Field && !((Field) object).isAccessible()) {
            ((Field) object).setAccessible(true); // [P3C 1.10 豁免] ClassUtils.setAccessible 本体（反射基础设施）
            if(Modifier.isPrivate(((Field) object).getModifiers()) && Modifier.isFinal(((Field) object).getModifiers())) {
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers"); // [P3C 1.10 豁免] ClassUtils.setAccessible 本体（移除 final 修饰需触碰修饰符元字段）
                    modifiersField.setAccessible(true); // [P3C 1.10 豁免] ClassUtils.setAccessible 本体（反射基础设施）
                    modifiersField.setInt( ((Field) object),  ((Field) object).getModifiers() & ~Modifier.FINAL);
                    return true;
                } catch (NoSuchFieldException e) {
                    return false;
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        if(object instanceof Constructor && !((Constructor) object).isAccessible()) {
            ((Constructor) object).setAccessible(true); // [P3C 1.10 豁免] ClassUtils.setAccessible 本体（反射基础设施）
            return true;
        }
        return false;
    }
    /**
     * 按名称读取目标类中的字段值（优先 getter 方法，回退直接字段访问）。
     *
     * @param fieldName 字段名称
     * @param target    目标类型
     * @param value     目标对象实例
     * @return 字段值，字段不存在时返回 null
     */
    public static Object getFieldValue(String fieldName, Class<?> target, Object value) {
        List<Field> fields = getFields(target);
        for (Field field : fields) {
            if (fieldName.equals(field.getName())) {
                return getFieldValue(field, target, value);
            }
        }
        return null;
    }
    /**
     * 读取指定字段的值（优先无参 getter 方法，回退直接字段访问）。
     *
     * @param field  目标字段对象，为 null 时返回 null
     * @param target 字段所属类型
     * @param value  目标对象实例
     * @return 字段值，方法或字段不存在时返回 null
     */
    public static Object getFieldValue(Field field, Class<?> target, Object value) {
        if (null == field) {
            return null;
        }
        String name = "get" + StringUtils.firstUpperCase(field.getName());
        try {
            Method declaredMethod = target.getDeclaredMethod(name); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（getFieldValue 优先 getter）
            if (null != declaredMethod) {
                setAccessible(declaredMethod);
                return declaredMethod.invoke(value);
            }
        } catch (Exception ignore) {
        }
        try {
            Method declaredMethod = target.getDeclaredMethod(field.getName()); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（getFieldValue 同名方法回退）
            if (null != declaredMethod) {
                setAccessible(declaredMethod);
                return declaredMethod.invoke(value);
            }
        } catch (Exception ignore) {
        }
        try {
            setAccessible(true);
            return field.get(value);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
    /**
     * 按名称读取 Bean 中的字段值（沿继承链查找字段后直接访问）。
     *
     * @param fieldName 字段名称，为 null 时返回 null
     * @param bean      目标对象，为 null 时返回 null
     * @return 字段值，字段不存在时返回 null
     */
    public static Object getFieldValue(String fieldName, Object bean) {
        if (null == fieldName || null == bean) {
            return null;
        }
        Field field = findField(bean.getClass(), fieldName);
        setAccessible(field);
        return null == field ? null : getFieldValue(field, bean);
    }
    /**
     * 读取指定字段的值（直接访问，设置可访问性）。
     *
     * @param field 目标字段对象，为 null 时返回 null
     * @param bean  目标对象
     * @return 字段值，访问失败时返回 null
     */
    public static Object getFieldValue(Field field, Object bean) {
        if (null == field) {
            return null;
        }
        try {
            return field.get(bean);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
    /**
     * 按名称设置 Bean 中的字段值（沿继承链查找字段，类型自动转换）。
     *
     * @param fieldName 字段名称，任一参数为 null/空时直接返回
     * @param value     待设置的值
     * @param bean      目标对象
     */
    public static void setFieldValue(String fieldName, Object value, Object bean) {
        if (ObjectUtils.isAnyEmpty(fieldName, value, bean)) {
            return;
        }
        setFieldValue(fieldName, bean.getClass(), value, bean);
    }
    /**
     * 按名称（忽略大小写）设置 Bean 中的字段值。
     *
     * @param fieldName 字段名称（忽略大小写匹配），任一参数为 null/空时直接返回
     * @param value     待设置的值
     * @param bean      目标对象
     * @since 1.0
     */
    public static void setIgnoreNameValue(String fieldName, Object value, Object bean) {
        if (ObjectUtils.isAnyEmpty(fieldName, value, bean)) {
            return;
        }
         List<Field> fields = getFields(bean.getClass());
        for (Field field : fields) {
            if (fieldName.equalsIgnoreCase(field.getName())) {
                value = Converter.convertIfNecessary(value, field.getType());
                setFieldValue(field, value, bean);
                break;
            }
        }
    }
    /**
     * 按名称在指定类型中查找字段并设置值（类型自动转换）。
     *
     * @param fieldName 字段名称，任一参数为 null/空时直接返回
     * @param type      目标类型
     * @param value     待设置的值
     * @param bean      目标对象
     * @since 1.0
     */
    public static void setFieldValue(String fieldName, Class<?> type, Object value, Object bean) {
        if (ObjectUtils.isAnyEmpty(fieldName, type, value)) {
            return;
        }
        List<Field> fields = getFields(type);
        for (Field field : fields) {
            if (fieldName.equals(field.getName())) {
                value = Converter.convertIfNecessary(value, field.getType());
                setFieldValue(field, value, bean);
                break;
            }
        }
    }
    /**
     * 设置指定字段值（静态字段：bean 为 null 时直接写入；实例字段：委托按类型查找）。
     *
     * <p>策略：
     * <ol>
     *   <li>静态字段（bean 为 null）：直接访问，final 字段先移除 final 修饰</li>
     *   <li>实例字段：委托 {@link #setFieldValue(Field, Class, Object, Object)} 查找 setter 方法</li>
     * </ol>
     * </p>
     *
     * @param field 目标字段，为 null 时直接返回
     * @param value 待设置的值
     * @param bean  目标对象，为 null 时表示静态字段
     * @since 1.0
     */
    public static void setFieldValue(Field field, Object value, Object bean) {
        if (null == field) {
            return;
        }
        if (null == bean) {
            setAccessible(field);
            try {
                if(Modifier.isFinal(field.getModifiers())) {
                    Field modifiers = Field.class.getDeclaredField("modifiers"); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（静态 final 字段需移除 final 修饰）
                    setAccessible(modifiers);
                    modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
                }
                field.set(null, value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }
        setFieldValue(field, bean.getClass(), value, bean);
    }
    /**
     * 设置指定字段值（优先 setter 方法，回退直接字段访问）。
     *
     * <p>策略：
     * <ol>
     *   <li>查找对应的 setter 方法（setXxx）并调用</li>
     *   <li>查找与字段同名的方法并调用</li>
     *   <li>直接设置字段值</li>
     * </ol>
     * </p>
     *
     * @param field  目标字段，为 null 时直接返回
     * @param target 字段所属类型
     * @param value  待设置的值
     * @param bean   目标对象
     * @since 1.0
     */
    public static void setFieldValue(Field field, Class<?> target, Object value, Object bean) {
        if (null == field) {
            return;
        }
        //                    setter                      
        String name = "set" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
        try {
            Method declaredMethod = findMethod(target, name, field.getType());
            if (null != declaredMethod) {
                setAccessible(declaredMethod);
                declaredMethod.invoke(bean, value);
                return;
            }
        } catch (Exception ignore) {
        }
        try {
            Method declaredMethod = target.getDeclaredMethod(field.getName(), field.getType()); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（setFieldValue 同名方法回退）
            if (null != declaredMethod) {
                setAccessible(declaredMethod);
                declaredMethod.invoke(bean, value);
                return;
            }
        } catch (Exception ignore) {
        }
        try {
            setAccessible(field);
            field.set(bean, value);
        } catch (IllegalAccessException e) {
        }
    }
    /**
     * 设置字段值（public setter 方法优先，回退直接字段访问）。
     *
     * @param field 目标字段
     * @param value 待设置的值（按字段类型自动转换）
     * @param type  字段所属类型
     * @param bean  目标对象
     * @param <T>   目标对象泛型
     *
     * <p>豁免说明：本类为反射基础设施，"setter 优先、字段访问回退"的赋值策略是工具类核心职责，
     * 允许在内部实现中直接使用 java.lang.reflect API。</p>
     */
    public static <T> void setAllFieldValue(Field field, Object value, Class<T> type, T bean) {
        Class<?> type1 = field.getType();
        value = Converter.convertIfNecessary(value, type1);
        String name = field.getName();
        try {
            Method method = type.getMethod("set" + Character.toUpperCase(name.charAt(0)) + name.substring(1), type1); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（setter 方法定位）
            if (null != method) {
                setAccessible(method);
                method.invoke(bean, value);
                return;
            }
        } catch (Exception ignore) {
        }
        try {
            setAccessible(field);
            field.set(bean, value);
        } catch (IllegalAccessException ignore) {
        }
    }
    /**
     * 判断源对象是否可赋值到目标类型（直接赋值或经转换）。
     *
     * @param targetType 目标类型
     * @param source     源对象，为 null 时返回 true
     * @return 目标类型可赋值或可转换时返回 true
     */
    public static boolean isEquals(Class<?> targetType, Object source) {
        if (null == source) {
            return true;
        }
        Class<?> aClass = source.getClass();
        if (targetType.isAssignableFrom(aClass)) {
            return true;
        }
        Object convertIfNecessary = Converter.convertIfNecessary(source, targetType);
        return null != convertIfNecessary;
    }
    /**
     * 读取字段值（通过 {@link InvocationHandler} 对象和字段引用）。
     *
     * @param object 目标对象（通常为代理对象）
     * @param field  目标字段，为 null 时返回 null
     * @return 字段值，访问失败时返回 null
     */
    public static Object invoke(InvocationHandler object, Field field) {
        setAccessible(field);
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
    /**
     * 将类名称字符串解析为 {@link Class} 实例，支持基本类型名和数组类名。
     *
     * <p>等价于 {@link #forName(String, ClassLoader)}，但将类加载失败包装为受检异常：</p>
     * <ul>
     *   <li>{@code IllegalAccessError} → {@link IllegalStateException}（继承层次可读性不匹配）</li>
     *   <li>{@code LinkageError} → {@link IllegalArgumentException}（类定义不可解析）</li>
     * </ul>
     *
     * @param className   类名称（支持 "int"、"java.lang.String[]" 等格式）
     * @param classLoader 类加载器，为 null 时使用默认类加载器
     * @return 解析得到的 Class 实例
     * @throws IllegalStateException    继承层次可读性不匹配（如 Jigsaw 模块定义缺少依赖声明）
     * @throws IllegalArgumentException 类名称不可解析（类找不到或类文件无法加载）
     * @see #forName(String, ClassLoader)
     */
    public static Class<?> resolveClassName(String className, ClassLoader classLoader)
            throws IllegalArgumentException {
        try {
            return forName(className, classLoader);
        } catch (IllegalAccessError err) {
            throw new IllegalStateException("Readability mismatch in inheritance hierarchy of class [" +
                    className + "]: " + err.getMessage(), err);
        } catch (LinkageError err) {
            throw new IllegalArgumentException("Unresolvable class definition for class [" + className + "]", err);
        }
    }
    /**
     * 判断值是否可赋值到指定类型（按类名解析目标类型后判断）。
     *
     * @param value  待判断的值
     * @param target 目标类型名称，为 null 时返回 false
     * @return 目标类型存在且值可赋值时返回 true
     */
    public static boolean isAssignableFrom(Object value, String target) {
        if (null == target) {
            return false;
        }
        if (!isPresent(target)) {
            return false;
        }
        return isAssignableFrom(value, forName(target));
    }
    /**
     * 判断值是否可赋值到指定类型。
     *
     * @param value  待判断的值，为 null 且目标类型为 void 时返回 true
     * @param target 目标类型
     * @return 可赋值时返回 true
     */
    public static boolean isAssignableFrom(Object value, Class<?> target) {
        if (null == value && isVoid(target)) {
            return true;
        }
        if (null == value) {
            return false;
        }
        return target.isAssignableFrom(value.getClass());
    }
    /**
     * 按目标类型转换并返回可赋值的值。
     *
     * @param value  待判断的值
     * @param target 目标类型
     * @param <T>    目标类型泛型
     * @return 值可赋值到目标类型时返回该值，否则返回 null
     */
    public static <T> T withAssignableFrom(Object value, Class<T> target) {
        if (null == value && isVoid(target)) {
            return null;
        }
        return target.isAssignableFrom(value.getClass()) ? (T) value : null;
    }
    /**
     * 获取类型的所有父类型（沿继承链向上收集父类和接口）。
     *
     * @param type 目标类型
     * @return 父类型集合（含所有父类和直接接口）
     */
    public static Set<Class<?>> getSuperType(Class<?> type) {
        Set<Class<?>> result = new HashSet<>(16);
        getSuperType(type, result);
        return result;
    }
    /**
     * 递归收集类型的父类与接口到结果集合（沿继承链向上）。
     *
     * @param type   当前类型，为 null 时直接返回
     * @param result 结果集合
     */
    private static void getSuperType(Class<?> type, Set<Class<?>> result) {
        if (null == type) {
            return;
        }
        Class<?> superclass = type.getSuperclass();
        Class<?>[] interfaces = type.getInterfaces();
        for (Class<?> anInterface : interfaces) {
            result.add(anInterface);
        }
        if (Object.class == superclass || null == superclass) {
            return;
        }
        result.add(superclass);
        getSuperType(superclass, result);
    }
    /**
     * 对类型实现的所有接口逐一执行回调（沿接口继承链收集）。
     *
     * @param type     目标类型
     * @param consumer 接口回调
     */
    public static void withInterface(Class<?> type, Consumer<Class<?>> consumer) {
        Set<Class<?>> result = new HashSet<>(16);
        loopInterfaces(type, result);
        for (Class<?> aClass : result) {
            consumer.accept(aClass);
        }
    }
    /**
     * 获取类型实现的所有接口集合（沿接口继承链收集）。
     *
     * @param type 目标类型
     * @return 所有接口的集合
     */
    public static Set<Class<?>> getAllInterfaces(Class<?> type) {
        Set<Class<?>> result = new HashSet<>(16);
        loopInterfaces(type, result);
        return result;
    }
    /**
     * 递归收集类型实现的所有接口到结果集合（沿接口继承链）。
     *
     * @param type   当前类型
     * @param result 结果集合
     */
    private static void loopInterfaces(Class<?> type, Set<Class<?>> result) {
        Class<?>[] interfaces = type.getInterfaces();
        for (Class<?> anInterface : interfaces) {
            result.add(anInterface);
            loopInterfaces(anInterface, result);
        }
    }
    /**
     * 对类型的所有父类型（沿继承链向上）逐一执行回调。
     *
     * @param type     目标类型
     * @param consumer 父类型回调
     */
    public static void withSuperType(Class<?> type, Consumer<Class<?>> consumer) {
        Set<Class<?>> result = new HashSet<>(16);
        getSuperType(type, result);
        for (Class<?> aClass : result) {
            consumer.accept(aClass);
        }
    }
    /**
     * 获取类加载器对应的 JAR 包根路径列表（含 classpath 与模块系统资源）。
     *
     * <p>仅对 {@link URLClassLoader} 生效：收集 getResources("") 资源、
     * getURLs() 声明的 JAR 路径，以及（系统类加载器时）java.class.path 属性路径。
     * 非 URLClassLoader 返回空列表。</p>
     *
     * @param classLoader 目标类加载器
     * @return 类加载器资源 URL 列表，非 URLClassLoader 时返回空列表
     */
    public static List<URL> classLoaderJarRoots(ClassLoader classLoader) {
        if (!(classLoader instanceof URLClassLoader)) {
            return Collections.emptyList();
        }
        // 收集类加载器资源
        Enumeration<URL> enumeration = null;
        try {
            enumeration = classLoader.getResources("");
        } catch (IOException e) {
            log.error("获取类加载器资源失败", e);
        }
        List<URL> rs = new LinkedList<>();
        while (enumeration.hasMoreElements()) {
            rs.add(enumeration.nextElement());
        }
        URL[] urls = ((URLClassLoader) classLoader).getURLs();
        rs.addAll(Arrays.asList(urls));
        // 系统类加载器时额外收集 java.class.path 属性中的路径
        if (classLoader == ClassLoader.getSystemClassLoader()) {
            String javaClassPathProperty = System.getProperty("java.class.path");
            for (String path : StringUtils.delimitedListToStringArray(javaClassPathProperty,
                    System.getProperty(SYMBOL_LEFT_SLASH))) {
                try {
                    rs.add(new File(path).toURI().toURL());
                } catch (Exception ignore) {
                }
            }
        }
        return rs;
    }
    /**
     * 从对象集合中查找可赋值到指定类型的唯一值。
     *
     * @param args   对象集合
     * @param aClass 目标类型
     * @return 匹配的唯一值，无匹配时返回 null
     */
    public static Object findOnlyOneValue(Collection<Object> args, Class<?> aClass) {
        return findOnlyOneValue(args.toArray(), aClass, 0);
    }
    /**
     * 从对象数组中查找可赋值到指定类型的唯一值（支持按索引定位）。
     *
     * @param args   对象数组，为 null 时返回 null
     * @param aClass 目标类型（基本类型自动转换为包装类型）
     * @param index  匹配索引偏移
     * @return 匹配的唯一值，无匹配时返回 null
     */
    public static Object findOnlyOneValue(Object[] args, Class<?> aClass, int index) {
        if (null == args) {
            return null;
        }
        aClass = fromPrimitive(aClass);
        // 收集 null 值与可赋值目标类型的参数
        List<Object> tpl = new LinkedList<>();
        for (Object arg : args) {
            if (null == arg || (null != arg && aClass.isAssignableFrom(arg.getClass()))) {
                tpl.add(arg);
            }
        }
        return CollectionUtils.find(tpl, index);
    }
    /**
     * 获取已标注元素的指定类型注解（支持 Class / Method / Field / Constructor）。
     *
     * @param type           目标元素（Class、Method、Field 或 Constructor）
     * @param annotationType 注解类型，为 null 时返回 null
     * @param <A>            注解类型泛型
     * @return 匹配类型的注解实例，未找到时返回 null
     */
    public static <A extends Annotation> A getDeclaredAnnotation(Object type, Class<? extends A> annotationType) {
        if (null == annotationType) {
            return null;
        }
        if (type instanceof Class) {
            return ((Class<?>) type).getDeclaredAnnotation(annotationType);
        }
        if (type instanceof Method) {
            return ((Method) type).getDeclaredAnnotation(annotationType);
        }
        if (type instanceof Field) {
            return ((Field) type).getDeclaredAnnotation(annotationType);
        }
        if (type instanceof Constructor) {
            return ((Constructor) type).getDeclaredAnnotation(annotationType);
        }
        return null;
    }
    /**
     * 获取指定类的类加载器（调用者类加载器）。
     *
     * @param caller 调用者类，不能为 null（null 时抛出 NullPointerException）
     * @return 调用者类的类加载器，为 null 时返回系统类加载器
     */
    public static ClassLoader getCallerClassLoader(Class<?> caller) {
        Preconditions.checkNotNull(caller);
        ClassLoader classLoader = caller.getClassLoader();
        return null == classLoader ? ClassLoader.getSystemClassLoader() : classLoader;
    }
    /**
     * 判断值是否可以赋值到指定类型（值可为 null）。
     *
     * @param type  目标类型
     * @param value 待判断的值，为 null 时判断目标类型是否非基本类型
     * @return 可赋值时返回 true
     */
    public static boolean isAssignableValue(Class<?> type, Object value) {
        return (value != null ? isAssignable(type, value.getClass()) : !type.isPrimitive());
    }
    /**
     * 判断右侧类型是否可赋值到左侧类型（含基本类型/包装类型互转）。
     *
     * @param lhsType 左侧类型（目标类型）
     * @param rhsType 右侧类型（源类型）
     * @return 可赋值时返回 true
     */
    public static boolean isAssignable(Class<?> lhsType, Class<?> rhsType) {
        if (lhsType.isAssignableFrom(rhsType)) {
            return true;
        }
        if (lhsType.isPrimitive()) {
            Class<?> resolvedPrimitive = convertIfPrimitive(rhsType);
            return (lhsType == resolvedPrimitive);
        }
        Class<?> resolvedWrapper = convertIfPrimitive(rhsType);
        return (resolvedWrapper != null && lhsType.isAssignableFrom(resolvedWrapper));
    }
    /**
     * 按名称获取类的所有方法（支持原始名与驼峰名双映射）。
     *
     * <p>对每个方法，同时按原始名称和下划线转驼峰名称各注册一条映射，
     * 便于通过不同命名风格查找同一方法。结果顺序与 {@link #getMethods} 一致。</p>
     *
     * @param type      目标类型
     * @param predicate 方法过滤条件，为 null 时不过滤
     * @return 方法名称到 Method 的映射（含驼峰名变体）
     */
    public static Map<String, Method> getMethodsByName(Class<?> type, Predicate<Method> predicate) {
            Map<String, Method> rs = new LinkedHashMap<>(64);
        doWithMethods(type, new SafeConsumer<Method>() {
            @Override
            /** 安全回调：注册方法的原始名与驼峰名映射 */
            public void safeAccept(Method method) throws Throwable {
                if (!predicate.test(method)) {
                    return;
                }
                rs.put(method.getName(), method);
                String name = method.getName();
                StringBuilder camel = new StringBuilder(name.length());
                boolean nextUpper = false;
                for (char c : name.toCharArray()) {
                    if (c == '_') {
                        nextUpper = true;
                    } else if (nextUpper) {
                        camel.append(Character.toUpperCase(c));
                        nextUpper = false;
                    } else {
                        camel.append(c);
                    }
                }
                rs.put(camel.toString(), method);
            }
        });
        return rs;
    }
    /**
     * 获取指定类的所有泛型类型参数（解析泛型父类或接口，结果缓存于 {@link #ACTUAL}）。
     *
     * <p>例如 {@code List<String>} 返回 {@code [String.class]}，
     * {@code Map<String, Integer>} 返回 {@code [String.class, Integer.class]}。</p>
     *
     * @param clazz 目标类，为 null 时返回空数组
     * @return 泛型类型参数数组，无泛型信息或解析失败时返回空数组
     * @since 2024/12/21
     */
    /**
     * 获取对象的泛型实际类型参数（按索引）。
     *
     * @param declaredClass 目标类，其泛型父类必须是 {@link ParameterizedType}
     * @param index         泛型参数索引（0-based）
     * @param <T>           目标类型泛型
     * @return 指定索引位置的泛型实际类型参数对应的 Class
     * @throws ArrayIndexOutOfBoundsException 当 index 超出实际类型参数范围时
     */
    public static <T> Class<T> resolveGenericType(Class<?> declaredClass, int index) {
        ParameterizedType parameterizedType = (ParameterizedType) declaredClass.getGenericSuperclass();
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        return (Class<T>) actualTypeArguments[index];
    }
    /**
     * 解析类的第一个泛型实际类型参数（索引 0）。
     *
     * @param declaredClass 目标类，其泛型父类必须是 {@link ParameterizedType}
     * @param <T>           目标类型泛型
     * @return 第一个泛型实际类型参数对应的 Class
     */
    public static <T> Class<T> resolveGenericType(Class<?> declaredClass) {
        return resolveGenericType(declaredClass, 0);
    }
    /**
     * 将方法的实参数组按形参类型做必要转换（{@link Converter#convertIfNecessary}）。
     *
     * @param method    目标方法
     * @param arguments 实参数组
     * @return 转换后的实参数组
     */
    public static Object[] toArgs(Method method, Object[] arguments) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] rs = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            rs[i] = Converter.convertIfNecessary(arguments[i], parameterType);
        }
        return rs;
    }
    /**
     * 获取类型的所有父类型与接口类型集合（保持插入顺序）。
     *
     * @param type 目标类型
     * @return 有序的类型集合（含所有父类与直接/间接接口）
     */
    public static Set<Class<?>> getAllType(Class<?> type) {
        Set<Class<?>> rs = new LinkedHashSet<>(16);
        getSuperType(type, rs);
        return rs;
    }
    /**
     * 解析返回类型的实际类型（数组类型还原为元素类型）。
     *
     * @param returnType 返回类型
     * @return 实际类型，数组类型时返回其元素类型，否则原样返回
     */
    public static Class<?> getActualType(Class<?> returnType) {
        if (returnType.isArray()) {
            return forName(returnType.getTypeName().replace("[]", ""), returnType.getClassLoader());
        }
        return returnType;
    }
    /**
     * 解析对象的实际类型（兼容代理类）。
     *
     * <p>普通对象返回其实际类；JDK 动态代理对象从其 {@code toString()} 结果中提取
     * 实现类名并解析；无法解析时返回 {@link Void#TYPE}。</p>
     *
     * @param obj 目标对象，为 null 时返回 {@link Void#TYPE}
     * @return 实际类型，无法解析时返回 {@code void.class}
     */
    public static Class<?> getActualType(Object obj) {
        if (null == obj) {
            return void.class;
        }
        if (obj instanceof Class) {
            return (Class<?>) obj;
        }
        Class<?> aClass = obj.getClass();
        if (!Proxy.isProxyClass(aClass)) {
            return aClass;
        }
        String s = obj.toString();
        int index = s.indexOf("@");
        String newName = s;
        if (index > -1) {
            newName = s.substring(0, index);
        }
        Class<?> aClass1 = ClassUtils.forName(newName);
        if (null != aClass1) {
            return aClass1;
        }
        return void.class;
    }
    /**
     * 使用默认类加载器读取资源流。
     *
     * @param s 资源路径（类路径形式）
     * @return 资源流，资源不存在时返回 null
     * @since 1.0
     */
    public static InputStream getResourceAsStream(String s) {
        return getDefaultClassLoader().getResourceAsStream(s);
    }
    /**
     * 获取基本类型的成员默认值。
     *
     * <ul>
     *   <li>{@code int} → 0</li>
     *   <li>{@code long} → 0L</li>
     *   <li>{@code float} → 0f</li>
     *   <li>{@code double} → 0d</li>
     *   <li>{@code boolean} → false</li>
     *   <li>{@code char} → 空格</li>
     *   <li>{@code short} → (short) 0</li>
     *   <li>{@code byte} → (byte) 0</li>
     * </ul>
     *
     * @param targetType 目标基本类型
     * @return 该基本类型的成员默认值
     * @since 1.0
     */
    public static Object memberDefault(Class<?> targetType) {
        if (targetType == int.class) {
            return 0;
        }
        if (targetType == long.class) {
            return 0L;
        }
        if (targetType == float.class) {
            return 0f;
        }
        if (targetType == double.class) {
            return 0d;
        }
        if (targetType == boolean.class) {
            return false;
        }
        if (targetType == char.class) {
            return ' ';
        }
        if (targetType == short.class) {
            return (short) 0;
        }
        return (byte) 0;
    }
    /**
     * 判断类型是否为基本类型的包装类。
     *
     * @param cls 待判断的类型，可以为 null（null 时返回 false）
     * @return 是基本类型包装类时返回 true，否则返回 false
     */
    public static boolean isPrimitiveWrapper(final Class<?> cls) {
        return null != primitiveToWrapper(cls);
    }
    /**
     * 将基本类型（primitive）转换为对应的包装器类型（wrapper）。
     *
     * <p>例如 {@code int.class} 转为 {@code Integer.class}，{@code void.class} 转为 {@code Void.class}；
     * 非基本类型原样返回。基本类型与包装器的映射表见 {@link #BASIC_VIRTUAL}。</p>
     *
     * @param cls 待转换的类型
     * @return 对应的包装器类型；入参为 null 或非基本类型时原样返回
     */
    public static Class<?> primitiveToWrapper(final Class<?> cls) {
        Class<?> convertedClass = cls;
        if (cls != null && cls.isPrimitive()) {
            Set<Object> objects = BASIC_VIRTUAL.rowKeySet();
            for (Object object : objects) {
                Map<Class<?>, Class<?>> row = BASIC_VIRTUAL.row(object);
                convertedClass = row.get(cls);
                if (null != convertedClass) {
                    return convertedClass;
                }
            }
        }
        return convertedClass;
    }
    /**
     * 将基本类型包装类字段值转换为 int 值。
     *
     * <p>按字段实际类型（经 {@link #primitiveToWrapper} 解析）分别处理：
     * 包装数字类型取 {@code intValue()}，字符取 {@link Character#getNumericValue}，
     * 布尔类型 true 返回 1、false 返回 0，未知类型返回 0。</p>
     *
     * @param fieldValue 字段值，不能为 null（其 {@code getClass()} 必须可解析）
     * @return 对应的 int 值，未知类型时返回 0
     */
    public static int primitiveInt(Object fieldValue) {
        Class<?> aClass = primitiveToWrapper(fieldValue.getClass());
        if(aClass == Integer.class) {
            return (int) fieldValue;
        }
        if(aClass == Long.class) {
            return ((Long) fieldValue).intValue();
        }
        if(aClass == Float.class) {
            return ((Float) fieldValue).intValue();
        }
        if(aClass == Double.class) {
            return ((Double) fieldValue).intValue();
        }
        if(aClass == Byte.class) {
            return ((Byte) fieldValue).intValue();
        }
        if(aClass == Short.class) {
            return ((Short) fieldValue).intValue();
        }
        if(aClass == Character.class) {
            return Character.getNumericValue((Character) fieldValue);
        }
        if(aClass == Boolean.class) {
            return ((Boolean) fieldValue) ? 1 : 0;
        }
        return 0;
    }
    /**
     * 通过 {@link Constructor} 反射实例化对象。
     *
     * @param declaredConstructor 目标构造器，不能为 null
     * @param args                构造参数
     * @param <T>                 目标类型泛型
     * @return 实例化成功的对象，失败时返回 null
     */
    public static <T>T newInstance(Constructor<T> declaredConstructor, Object...args) {
        try {
            setAccessible(declaredConstructor);
            return (T) declaredConstructor.newInstance(args); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（newInstance(Constructor) 对外封装）
        } catch (Exception e) {
        }
        return null;
    }
    /**
     * 按类名/类对象和参数反射实例化对象（优先匹配同参数数量的构造器）。
     *
     * <p>查找目标类的所有已声明构造器，按参数数量匹配并调用；若无参构造器未找到，
     * 尝试静态构建器方法（{@code builder()}）；仍失败时委托 {@link ReflectUtils#instantiate}。</p>
     *
     * @param protoMapClass 目标类型，不能为 null
     * @param args          构造参数
     * @param <T>           目标类型泛型
     * @return 实例化成功的对象
     * @throws RuntimeException 实例化失败（InstantiationException / IllegalAccessException /
     *                          InvocationTargetException / NoSuchMethodException）时抛出
     *
     * <p>豁免说明：本类为反射基础设施，枚举构造器并按参数个数匹配调用是工具类核心职责，
     * 允许在内部实现中直接使用 java.lang.reflect API。</p>
     */
    public static <T>T newInstance(Class<T> protoMapClass, Object...args) {
        try {
            Constructor<?>[] declaredConstructors = protoMapClass.getDeclaredConstructors(); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（newInstance 构造器枚举匹配）
            for (Constructor<?> declaredConstructor : declaredConstructors) {
                if(declaredConstructor.getParameterCount() == args.length) {
                    try {
                        setAccessible(declaredConstructor);
                        return (T) declaredConstructor.newInstance(args); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（newInstance(Class) 构造器匹配实例化）
                    } catch (Exception e) {
                    }
                }
            }
            if(args.length == 0) {
                return staticBuilderMethod(protoMapClass);
            }
            return ReflectUtils.instantiate(protoMapClass, args);
        } catch (Exception e) {
            // 构造器调用失败（参数不匹配等），继续尝试下一个构造器
            throw new RuntimeException(e);
        }
    }
    /**
     * 查找类的静态 {@code builder()} 方法并调用构建，返回构建结果。
     *
     * @param protoMapClass 目标类
     * @param <T> 目标类型泛型
     * @return 静态构建器方法的调用结果
     * @throws RuntimeException 类不存在静态 builder() 方法时抛出
     */
    private static <T> T staticBuilderMethod(Class<T> protoMapClass) {
        Method builder = null;
        try {
            builder = protoMapClass.getMethod("builder");
        } catch (NoSuchMethodException ignored) {
        }
        if(null != builder) {
            ClassUtils.setAccessible(builder);
            return (T) ClassUtils.invokeBean(ClassUtils.invokeMethod(builder, null), "build");
        }
        throw new RuntimeException(String.format("               %s", protoMapClass.getName()));
    }
    /**
     * 将 Map 数据映射到目标类型对象（先实例化目标对象，再复制属性）。
     *
     * @param item      源 Map 数据
     * @param targetType 目标类型
     * @param <T>       目标类型泛型
     * @return 填充完成的目标对象
     * @since 1.0
     */
    public static <T> T mapToObject(Map<String, Object> item, Class<T> targetType) {
        T t = forObject(targetType);
        BeanUtils.copyProperties(item, t);
        return t;
    }
    /**
     * 将 Map 数据映射到目标类型对象（按构造器参数名匹配 Map 键，反射实例化）。
     *
     * <p>先尝试无参或带参构造器直接创建；失败时遍历目标类所有已声明构造器，
     * 按 {@link Parameter#getName()} 与 Map 键匹配生成实参并反射调用。</p>
     *
     * <p>豁免说明：本类为反射基础设施，构造器反射调用属其核心职责，
     * 未收敛到 {@link com.chua.common.support.reflection.ReflectUtils}。</p>
     *
     * @param target 目标类型
     * @param map    源 Map 数据（键为参数名）
     * @param args   附加参数
     * @param <T>    目标类型泛型
     * @return 实例化成功的对象，无法匹配构造器时返回 null
     */
    public static <T> T forObjectOfMap(Class<T> target, Map map, Object... args) {
        T t = forObject(target, args);
        if(null != t) {
            return t;
        }
        Constructor<?>[] declaredConstructors = target.getDeclaredConstructors(); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（forObjectOfMap 构造器枚举匹配）
        for (Constructor<?> declaredConstructor : declaredConstructors) {
            Parameter[] parameters = declaredConstructor.getParameters();
            Object[] newArgs = createArgs(parameters, map);
            if(null == newArgs) {
                continue;
            }
            setAccessible(declaredConstructor);
            try {
                return (T) declaredConstructor.newInstance(newArgs); // [P3C 1.10 豁免] ClassUtils 为反射基础设施本体（forObjectOfMap 构造器反射实例化）
            } catch (Exception ignore) {
            }
        }
        return null;
    }
    /**
     * 按构造器参数名从 Map 中提取实参（null 占比超过 60% 时放弃匹配）。
     *
     * @param parameters 构造器参数列表
     * @param map        源 Map（键为参数名）
     * @return 与参数数量等长的实参数组，匹配失败时返回 null
     */
    private static Object[] createArgs(Parameter[] parameters, Map map) {
        Object[] value = new Object[parameters.length];
        int nullCount = 0;
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String name = parameter.getName();
            if (!map.containsKey(name)) {
                nullCount++;
                continue;
            }
            value[i] = map.get(name);
        }
        if(nullCount >= parameters.length * 0.6) {
            return null;
        }
        return value;
    }
    /**
     * 将反射对象包装为 {@link PrivilegedAction}，在受保护上下文执行 {@link #setAccessible}。
     *
     * @param <T> 目标反射对象类型（须继承 {@link AccessibleObject}）
     */
    static class SetAccessibleAction<T extends AccessibleObject> implements PrivilegedAction<T> {
        /** 待设置可访问性的反射对象 */
        private final T obj;
        /**
         * 创建设置 accessible 动作实例。
         *
         * @param obj 目标反射对象
         */
        public SetAccessibleAction(T obj) {
            this.obj = obj;
        }
        @Override
        /** 执行动作：设置目标对象可访问性并返回 */
        public T run() {
            setAccessible(obj);
            return obj;
        }
    }
 // 简类名工具
    // ----------------------------------------------------------------------
    /**
     * 获取对象的简类名（不含包名），对象为 null 时返回指定默认值。
     *
     * @param object      目标对象，可以为 null
     * @param valueIfNull 对象为 null 时返回的值
     * @return 对象类名的简名，对象为 null 时返回 valueIfNull
     */
    public static String getShortClassName(final Object object, final String valueIfNull) {
        if (object == null) {
            return valueIfNull;
        }
        return getShortClassName(object.getClass());
    }
    /**
     * 获取类的简类名（不含包名），类为 null 时返回空字符串。
     *
     * <p>内部类用 {@code .} 替代 {@code $} 分隔（如 {@code Map.Entry}）。</p>
     *
     * @param cls 目标类，可以为 null
     * @return 简类名，类为 null 时返回空字符串
     */
    public static String getShortClassName(final Class<?> cls) {
        if (cls == null) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        return getShortClassName(cls.getName());
    }
    /**
     * 获取类名简名（不含包名），支持 {@code Class.getName()} 格式（JVM 内部格式）。
     *
     * <p>处理规则：
     * <ol>
     *   <li>数组类型编码（{@code [L...;}/{@code [[I} 等）→ 还原为 {@code 元素类型[]}</li>
     *   <li>内部类分隔符 {@code $} 替换为 {@code .}</li>
     *   <li>截断包名（最后一个 {@code .} 之后）</li>
     * </ol>
     * </p>
     *
     * @param className 类名（{@code Class.getName()} 格式，如 {@code java.util.Map$Entry}、{@code [Ljava.lang.String;}）
     * @return 简类名，输入为空时返回空字符串
     */
    public static String getShortClassName(String className) {
        if (StringUtils.isEmpty(className)) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        final StringBuilder arrayPrefix = new StringBuilder(32);
 // 处理 array 编码
        if (className.startsWith("[")) {
            while (className.charAt(0) == '[') {
                className = className.substring(1);
                arrayPrefix.append("[]");
            }
 // Strip 对象 类型 编码
            if (className.charAt(0) == 'L' && className.charAt(className.length() - 1) == ';') {
                className = className.substring(1, className.length() - 1);
            }
            if (REVERSE_ABBREVIATION_MAP.containsKey(className)) {
                className = REVERSE_ABBREVIATION_MAP.get(className);
            }
        }
        final int lastDotIdx = className.lastIndexOf(PACKAGE_SEPARATOR_CHAR);
        final int innerIdx = className.indexOf(
                INNER_CLASS_SEPARATOR_CHAR, lastDotIdx == -1 ? 0 : lastDotIdx + 1);
        String out = className.substring(lastDotIdx + 1);
        if (innerIdx != -1) {
            out = out.replace(INNER_CLASS_SEPARATOR_CHAR, PACKAGE_SEPARATOR_CHAR);
        }
        return out + arrayPrefix;
    }
 // Generics 类型 resolution
    // -----------------------------------------------------------------------
    /**
     * 获取指定索引位置的泛型实际类型参数。
     *
     * <p>例如 {@code List<String>} 的 {@code getGenericType(clazz, 0)} 返回 {@code String.class}。</p>
     *
     * @param clazz 目标类
     * @param index 泛型参数索引（0-based），负数时抛出异常
     * @return 该索引位置的 {@link Class}，非 Class/ParameterizedType 时返回 null
     * @throws IllegalArgumentException 当 index 为负数时
     * @since 2024/12/21
     */
    public static Class<?> getGenericType(Class<?> clazz, int index) {
        if (clazz == null || index < 0) {
            if (index < 0) {
                throw new IllegalArgumentException("index 不能为负数: " + index);
            }
            return null;
        }
        try {
            Type[] genericTypes = getGenericTypes(clazz);
            if (genericTypes != null && index < genericTypes.length) {
                Type type = genericTypes[index];
                if (type instanceof Class) {
                    return (Class<?>) type;
                } else if (type instanceof ParameterizedType) {
                    // 参数化类型取原始类型
                    return (Class<?>) ((ParameterizedType) type).getRawType();
                }
            }
            return null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
            log.debug("[getGenericType] 解析泛型类型失败: class={}, index={}", clazz.getName(), index, e);
            }
            return null;
        }
    }
    /**
     * 获取类的所有泛型实际类型参数（沿父类和接口解析，结果缓存于 {@link #ACTUAL}）。
     *
     * <p>例如 {@code List<String>} 返回 {@code [String.class]}，
     * {@code Map<String, Integer>} 返回 {@code [String.class, Integer.class]}。</p>
     *
     * @param clazz 目标类，为 null 时返回空数组
     * @return 泛型实际类型参数数组（非 Class 类型回退为 Object.class），无泛型信息时返回空数组
     * @since 2024/12/21
     */
    public static Class<?>[] getGenericTypes(Class<?> clazz) {
        if (clazz == null) {
            return new Class<?>[0];
        }
        // 命中缓存直接返回
        Type[] cachedTypes = ACTUAL.get(clazz);
        if (cachedTypes != null) {
            return convertToClassArray(cachedTypes);
        }
        try {
            Type[] types = resolveGenericTypes(clazz);
            // 缓存解析结果
            if (types != null && types.length > 0) {
                ACTUAL.put(clazz, types);
            } else {
                // 无泛型信息也缓存空数组避免重复解析
                ACTUAL.put(clazz, new Type[0]);
            }
            return convertToClassArray(types);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[getGenericTypes] 解析泛型类型失败: class={}", clazz.getName(), e);
            }
            // 解析失败缓存空数组并返回
            ACTUAL.put(clazz, new Type[0]);
            return new Class<?>[0];
        }
    }
    /**
     * 解析类的泛型实际类型参数（先查接口，再查父类）。
     *
     * @param clazz 目标类，为 null 时返回空数组
     * @return 泛型实际类型参数数组，无泛型信息时返回空数组
     */
    private static Type[] resolveGenericTypes(Class<?> clazz) {
        if (clazz == null) {
            return new Type[0];
        }
        // 优先从泛型接口中提取
        Type[] genericInterfaces = clazz.getGenericInterfaces();
        for (Type genericInterface : genericInterfaces) {
            Type[] types = extractGenericTypes(genericInterface);
            if (types != null && types.length > 0) {
                return types;
            }
        }
        // 回退到泛型父类
        Type genericSuperclass = clazz.getGenericSuperclass();
        Type[] types = extractGenericTypes(genericSuperclass);
        if (types != null && types.length > 0) {
            return types;
        }
        return new Type[0];
    }
    /**
     * 从泛型类型中提取实际类型参数。
     *
     * @param type 泛型类型，必须是 {@link ParameterizedType} 才有效
     * @return 实际类型参数数组，非参数化类型或无参数时返回 null
     */
    private static Type[] extractGenericTypes(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments != null && actualTypeArguments.length > 0) {
                return actualTypeArguments;
            }
        }
        return null;
    }
    /**
     * 将泛型类型数组转换为 Class 数组（无法识别的类型回退为 Object.class）。
     *
     * @param types 泛型类型数组
     * @return Class 数组，输入为 null 或空时返回空数组
     */
    private static Class<?>[] convertToClassArray(Type[] types) {
        if (types == null || types.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] classes = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            Type type = types[i];
            if (type instanceof Class) {
                classes[i] = (Class<?>) type;
            } else if (type instanceof ParameterizedType) {
                // 参数化类型取原始类型
                classes[i] = (Class<?>) ((ParameterizedType) type).getRawType();
            } else if (type instanceof TypeVariable) {
                // 类型变量无法在运行时解析，回退为 Object.class
                classes[i] = Object.class;
            } else if (type instanceof WildcardType) {
                // 通配符无法在运行时解析，回退为 Object.class
                classes[i] = Object.class;
            } else {
                // 其他未知类型回退为 Object.class
                classes[i] = Object.class;
            }
        }
        return classes;
    }
    /**
     * 清空泛型类型参数缓存（{@link #ACTUAL}）。
     *
     * @since 2024/12/21
     */
    public static void clearGenericTypeCache() {
        ACTUAL.clear();
        if (log.isDebugEnabled()) {
            log.debug("[clearGenericTypeCache] 泛型类型缓存已清空");
        }
    }
}
