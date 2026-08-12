package com.chua.common.support.utils;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.function.MethodFilter;
import com.chua.common.support.function.SafeConsumer;
import com.chua.common.support.lang.reflect.MethodInvoker;
import com.chua.common.support.modules.ModuleLoader;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import javax.xml.crypto.dsig.keyinfo.KeyValue;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.PrivilegedAction;
import java.util.*;
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
 */
@SuppressWarnings("ALL")
public class ClassUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClassUtils.class);

    /**
     *       .
     * Key             Value                    JDK          
     *        ConcurrentReferenceHashMap                512                              
     */
    private static final Map<String, Boolean> CACHE = new ConcurrentReferenceHashMap<>(512);
    /**
     *             .
     * Key                   Value              Method       
     *        ConcurrentReferenceHashMap                512                              
     */
    private static Map<Integer, Method> cacheMethod = new ConcurrentReferenceHashMap<>(512);
    /**
     *                   : {@code '$' == {@value}}.
     *                             Outer$Inner        $
     */
    public static final char INNER_CLASS_SEPARATOR_CHAR = '$';

    /**
     *                : {@code '&#x2e;' == {@value}}.
     *                                            com.example.utils        .
     */
    public static final char PACKAGE_SEPARATOR_CHAR = '.';

    /**
     *           JDK                .
     *        Java                                            JDK                                  
     *          java   javax   jdk   oracle     sun          
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
     *        ConcurrentReferenceHashMap          Key             Value                                  
     *                 256                                          
     */
    private static final Map<Class<?>, Method[]> DECLARED_METHODS_CACHE = new ConcurrentReferenceHashMap<>(256);

    /**
     *                                           .
     *                       int.class                                        Integer.class   
     *                       Map                                     
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
     *          CGLIB   Spring                Javassist   Apache IBatis          
     */
    private static final List<String> PROXY_CLASS_NAMES = Arrays.asList("net.sf.cglib.proxy.Factory"
            // cglib
            , "org.springframework.cglib.proxy.Factory"
            , "javassist.util.proxy.ProxyObject"
            // javassist
            , "org.apache.ibatis.javassist.util.proxy.ProxyObject");

    /**
     *                               .
     * Key             Value                                  
     *        ConcurrentReferenceHashMap                                                  256
     */
    protected static final Map<Class<?>, Type[]> ACTUAL = new ConcurrentReferenceHashMap<>(256);

    /**
     *                                                          .
     * Key             Value                                              
     *        ConcurrentReferenceHashMap                256                              
     */
    protected static final Map<Class<?>, List<Field>> CLASS_FIELD = new ConcurrentReferenceHashMap<>(256);

    /**
     *                                                                         .
     * Key             Value                                                                   
     *        ConcurrentReferenceHashMap                256
     */
    protected static final Map<Class<?>, List<Field>> CLASS_FIELD_LOCAL = new ConcurrentReferenceHashMap<>(256);

    /**
     *                                                                         .
     * Key             Value                                  
     *        ConcurrentReferenceHashMap                256                              
     */
    protected static final Map<Class<?>, List<Method>> CLASS_METHOD = new ConcurrentReferenceHashMap<>(256);

    /**
     *                                                                         .
     * Key             Value                                                                   
     *        ConcurrentReferenceHashMap                256
     */
    protected static final Map<Class<?>, List<Method>> CLASS_METHOD_LOCAL = new ConcurrentReferenceHashMap<>(256);

    /**
     *                                     .
     *                       Integer.class                               int.class   
     *        IdentityHashMap                                         9
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_TYPE_MAP = new IdentityHashMap<>(9);

    /**
     *                                     .
     *                       int.class                                        Integer.class   
     *        IdentityHashMap                                         9
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TYPE_TO_WRAPPER_MAP = new IdentityHashMap<>(9);

    /**
     *                                     .
     *                                "int"                                        int.class   
     *        ConcurrentReferenceHashMap                32                              
     */
    private static final Map<String, Class<?>> PRIMITIVE_TYPE_NAME_MAP = new ConcurrentReferenceHashMap<>(32);

    /**
     *        Java           Class                .
     *                 Java           String   Integer   List                                  
     *        ConcurrentReferenceHashMap                64
     */
    private static final Map<String, Class<?>> COMMON_CLASS_CACHE = new ConcurrentReferenceHashMap<>(64);

    /**
     *           Class                      .
     *                    forName                                        
     *        ConcurrentReferenceHashMap                1024                              
     */
    private static final Map<String, Class<?>> CLASS_NAME_CACHE = new ConcurrentReferenceHashMap<>(1024);

    /**
     *                      : {@code "[]"}.
     *                                      "String[]"     "int[]"
     */
    public static final String ARRAY_SUFFIX = "[]";

    /**
     *                            : {@code CommonConstant.SYMBOL_LEFT_SQUARE_BRACKET}.
     *        JVM                                      "["
     */
    private static final String INTERNAL_ARRAY_PREFIX = SYMBOL_LEFT_SQUARE_BRACKET;

    /**
     *                               : {@code "[L"}.
     *                                      "[Ljava.lang.String;"
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
     *                                         Outer$Inner
     */
    private static final char NESTED_CLASS_SEPARATOR = '$';

    /**
     * Java                   .
     *        Java                                                                                     
     *          Serializable   Cloneable   Comparable    
     */
    private static final Set<Class<?>> JAVA_LANGUAGE_INTERFACES;

    /**
     *                                                       .
     *     JVM                                "I"                               "int"   
     *                               
     */
    private static final Map<String, String> REVERSE_ABBREVIATION_MAP;

    // Feed abbreviation maps
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

        // Map entry iteration is less expensive to initialize than forEach with lambdas
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
 * @author CH
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
 * @author CH
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
 * @author CH
     * @since 1.0
     */
    public static <T> boolean isVoid(T value) {
        return null == value || isVoid(toType(value));
    }

    /**
     *                                        .
     *
     *                               
     * 1.                                                 
     * 2.                       ClassUtils                      
     * 3.                                           
     *
     * @return                                                                    null
 * @author CH
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
     * 已被 {@link ScriptDefinition} 主动管理的 ClassLoader 集合。
     * <p>用于避免 GC 时 ClassLoader 泄漏（脚本编译产生的 ClassLoader 持有大量 Metaspace）。
     * 弱引用保证 ClassLoader 不再被业务引用时可被 GC 回收，本集合不阻止回收。</p>
     */
    private static final java.util.Set<java.lang.ref.WeakReference<ClassLoader>> REGISTERED_CLASS_LOADERS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * 将 ClassLoader 注册到全局弱引用集合中，便于后续清理时遍历释放。
     * <p>主要用于脚本引擎（Groovy/JS/Python 等）动态编译产生的 ClassLoader，
     * 它们持有大量已编译类可能引发 Metaspace 泄漏。
     * 调用方应自行保证 ClassLoader 唯一，否则该方法会被相同 ClassLoader 重复注册。</p>
     *
     * @param classLoader 待注册的 ClassLoader，null 时不处理
     */
    public static void registerClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        REGISTERED_CLASS_LOADERS.add(new java.lang.ref.WeakReference<>(classLoader));
    }

    /**
     * 注销（清理）指定的 ClassLoader。
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
     * @param classLoader 待注销的 ClassLoader，null 时不处理
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
     * 清理并注销所有已注册的 ClassLoader。
     * <p>遍历 {@link #REGISTERED_CLASS_LOADERS} 中所有仍然存活（未被 GC）的 ClassLoader，
     * 逐一调用 {@link #unregisterClassLoader(ClassLoader)}。
     * 已被 GC 回收的弱引用直接移除。</p>
     *
     * @return 实际注销的 ClassLoader 数量
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
     * 当指定类名存在并可解析时，执行回调函数。
     *
     * @param className 待检查的类名
     * @param consumer 解析成功后的回调，接收 {@link Class} 对象
 * @author CH
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
 * @author CH
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
 * @author CH
     * @since 1.0
     */
    public static <T> void isPresent(final String className, Class<T> type, Consumer<T> consumer) {
        if (!isPresent(className)) { return; }

        Class<?> aClass = forName(className);
        if (null == aClass || !type.isAssignableFrom(aClass)) { return; }

        consumer.accept((T) forObject(aClass));
    }

    /**
     * 判断指定类名是否存在于当前默认类加载器可见范围内。
     *
     * @param className 待判断的类名
     * @return 如果类可被解析则返回 {@code true}，否则返回 {@code false}
 * @author CH
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
 * @author CH
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
     *                                      Class       .
     *
     *                               
     * -                      com.example.User
     * -                java.lang.String[]
     * - JVM                      [Ljava.lang.String;
     * -                int, boolean    
     *
     * @param name                             null
     * @return              Class                                                  null
 * @author CH
     * @since 1.0
     */
    public static Class<?> forName(String name) {
        if (null == name) { return null; }

        return forName(name, getDefaultClassLoader());
    }

    /**
     *                                      Class                                           .
     *
     *                                                                                              
     *                                                                                        
     *
     * @param name                   
     * @param classLoader                               
     * @return              Class       
     * @throws ClassNotFoundException                                  
     * @throws RuntimeException     ClassNotFoundException                         
 * @author CH
     * @since 1.0
     */
    public static Class<?> toClassConfident(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, true, classLoader);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name);
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
 * @author CH
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
 * @author CH
     * @since 1.0
     */
    public static <T> Class<T> forName(String name, Class<T> returnType) {
        Class<?> aClass = null;
        try {
            aClass = forName(name);
        } catch (Exception e) {
            return null;
        }
        return null == aClass || returnType.isAssignableFrom(aClass) ? (Class<T>) aClass : null;
    }

    /**
     *                                                                 Class       .
     *
     *                               
     * -                      com.example.User
     * -                java.lang.String[]   [[Ljava.lang.String;
     * -                int   boolean                                     
     * -             java.util.Map.Entry                      java.util.Map$Entry   
     *
     * @param name                   
     * @param classLoader                                         null                            
     * @return              Class                                                  null
 * @author CH
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
            clazz = Class.forName(name, false, clToUse);
            CLASS_NAME_CACHE.put(name, clazz);
            return clazz;
        } catch (ClassNotFoundException ex) {
            int lastDotIndex = name.lastIndexOf(PACKAGE_SEPARATOR);
            if (lastDotIndex != -1) {
                String nestedClassName =
                        name.substring(0, lastDotIndex) + NESTED_CLASS_SEPARATOR + name.substring(lastDotIndex + 1);
                try {
                    clazz = Class.forName(nestedClassName, false, clToUse);
                    CLASS_NAME_CACHE.put(name, clazz);
                    return clazz;
                } catch (ClassNotFoundException ex2) {
                    //                                null
                }
            }
            return null;
        }
    }

    /**
     * 根据基本类型名称解析对应的 {@link Class} 对象。
     *
     * @param name 基本类型名称，如 {@code int}、{@code boolean} 等
     * @return 如果名称对应基本类型则返回对应 {@link Class}，否则返回 {@code null}
 * @author CH
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
 * @author CH
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
 * @author CH
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
 * @author CH
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
     *        Class                                              .
     *
     *                         
     * 1.                       List   Collection     Set                                                 
     * 2.                                                                            
     * 3.                                                                         
     * 4.                                                                                  
     *
     *       /                     
     * 1.                          null                                 
     *
     * @param tClass                   
     * @param classLoader                               
     * @param params                                                 
     * @param <T>                               
     * @return                                                     null
     * @throws Exception                                  
 * @author CH
     * @since 1.0
     */
    public static <T> T forObject(Class<T> tClass, ClassLoader classLoader, Object... params) throws Exception {
        if (null == tClass) { return null; }

        if (List.class.isAssignableFrom(tClass) || Collection.class.isAssignableFrom(tClass)) {
            return (T) new ArrayList();
        }

        if (Set.class.isAssignableFrom(tClass)) {
            return (T) new HashSet<>();
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
                return declaredConstructor.newInstance(params);
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
 * @author CH
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
 * @author CH
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
     */
    private static <T> T createAlgorithm(Class<T> tClass, Object[] params) {
        Map<Constructor<?>, Object[]> loss = new LinkedHashMap<>();
        Map<Constructor<?>, Object[]> allnull = new LinkedHashMap<>();

        Map<Class<?>, Object> typeAndValue = createTypeAndValue(params);
        Constructor<?>[] declaredConstructors = tClass.getDeclaredConstructors();
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
                    Object newInstance = constructor.newInstance(entry.getValue());
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
                    Object newInstance = constructor.newInstance(entry.getValue());
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
            return (T) declaredConstructor.newInstance();
        }

        Object[] args = getArgs(parameterTypes, params);

        if (isAllNull(args)) {
            allNull.put(declaredConstructor, args);
            return null;
        }

        if (hasNone(args)) {
            setAccessible(declaredConstructor);
            return (T) declaredConstructor.newInstance(args);
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
     *                   
     * <p>1.                     </p>
     * <p>2.                  </p>
     *
     * @param value    
     * @return             
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
     *       ClassPath                                                <br>
     *       ClassPath                                       
     *
     * @return ClassPath
     */
    public static String getClassPath() {
        return getClassPath(false);
    }

    /**
     *       ClassPath         ClassPath                                       
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
     *       ClassPath URL
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
     * @param source                Classpath            
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
     * java   
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
 * @author CH
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
     * @return           Field                                   null
 * @author CH
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
     *                                                                       Object                
     *
     * @param obj       
     * @return                 List                   null              List
 * @author CH
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
 * @author CH
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
 * @author CH
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
     * @return                 List                               List
 * @author CH
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
     *                                                                    Object    
     *
     * @param type                
     * @param name             
     * @return           Field                                   null
 * @author CH
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
        Set<Method> cache = new HashSet<>();

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
            Set<Class<?>> allInterfaces = new HashSet<>();
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
     */
    public static <T> Constructor<T> getConstructor(Class<T> tClass, Class<?>[] classes) {
        if (null == tClass || null == classes) {
            return null;
        }
        Constructor<?>[] declaredConstructors = tClass.getDeclaredConstructors();
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
     *             Object.class
     *
     * @param clazz    
     * @return        Object.class       null       true
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
     *             
     *
     * @param type             
     * @param methodName       
     * @param args             
     * @return       
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
     *             
     * <p>
     *                       {@link MethodInvoker}                   
     * </p>
     *
     * @param methodHandle             
     * @param bean               
     * @param args               
     * @return       
     */
    public static Object invokeMethod(MethodHandle methodHandle, Object bean, Object... args) {
        if (null == methodHandle) {
            return null;
        }

        //        Callable             
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
     *             
     * <p>
     *                       {@link MethodInvoker}                      
     *        JDK                    MethodHandle          
     * </p>
     *
     * @param method       
     * @param bean         
     * @param args         
     * @return       
     */
    public static Object invokeMethod(Method method, Object bean, Object... args) {
        if (null == method) {
            return null;
        }

        //        Callable             
        if (bean instanceof Callable) {
            try {
                return ((Callable<?>) bean).call();
            } catch (Exception ignored) {
            }
            return null;
        }

        //                   
        if (method.isDefault()) {
            return invokeDefaultMethod(method, bean, args);
        }

        //                      
        try {
            return MethodInvoker.invoke(method, bean, args);
        } catch (RuntimeException e) {
            //                                                    
            if (e.getCause() != null && 
                "object is not an instance of declaring class".equalsIgnoreCase(e.getCause().getLocalizedMessage())) {
                return ClassUtils.invokeBean(bean, method.getName(), method.getParameterTypes(), args);
            }
            throw e;
        }
    }
    /**
     *        MethodHandle
     *
     * @param methodHandle
     * @param obj
     * @param args
     * @return
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
                        .getDeclaredConstructor(Class.class, Class.class, int.class);
            } catch (NoSuchMethodException e) {
                constructor = MethodHandles.Lookup.class
                        .getDeclaredConstructor(Class.class, int.class);
            }

            setAccessible(constructor);
            Class<?> declaringClass = method.getDeclaringClass();
            int allModes = MethodHandles.Lookup.PUBLIC | MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED | MethodHandles.Lookup.PACKAGE;
            return (constructor.getParameterCount() == 2 ? ((MethodHandles.Lookup) constructor.newInstance(declaringClass, allModes)) :
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
     *                                                 .
     *
     *                                                                                                          
     *
     *                
     * -                            
     * -                       null                                                   
     * -                                                                         
     *
     * @param clazz                
     * @param name             
     * @param paramTypes                                   null                      
     * @return           Method                                   null
 * @author CH
     * @since 1.0
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        return cacheMethod.computeIfAbsent(clazz.hashCode() + name.hashCode() + paramTypes.hashCode(), new Function<Integer, Method>() {
            @Override
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
     *                                                                                        
     *
     * @param clazz         
     * @param name                
     * @param paramTypes                         
     * @return           Method                                   null
 * @author CH
     * @since 1.0
     */
    public static Method findDeclaredMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * get-all      
     *
     * @param clazz clazz
     * @return {@link Method[]}
     */
    private static Method[] getAllMethod(Class<?> clazz) {
        return DECLARED_METHODS_CACHE.computeIfAbsent(clazz, new Function<Class<?>, Method[]>() {
            @Override
            public Method[] apply(Class<?> aClass) {
                List<Method> rs = new LinkedList<>();
                List<Class<?>> validate = new LinkedList<>();
                doRegisterMethod(rs, aClass, validate);
                return rs.toArray(new Method[0]);

            }

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
     *                                            true
     *
     * @param object                   
     * @param <T>          
     * @return                         
     */
    public static boolean setAccessible(Object object) {
        ModuleLoader.exportAllToAll();
        if(null == object) {
            return false;
        }


        if(object instanceof Method && !((Method) object).isAccessible()) {
            //        return AccessController.doPrivileged(new SetAccessibleAction<>(object));
            ((Method) object).setAccessible(true);
            return true;
        }

        if(object instanceof Field && !((Field) object).isAccessible()) {
            ((Field) object).setAccessible(true);
            if(Modifier.isPrivate(((Field) object).getModifiers()) && Modifier.isFinal(((Field) object).getModifiers())) {
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setAccessible(true);
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
            ((Constructor) object).setAccessible(true);
            return true;
        }
        return false;
    }


    /**
     *                
     *
     * @param fieldName       
     * @param target          
     * @param value        
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
     *                
     *
     * @param field        
     * @param target       
     * @param value     
     */
    public static Object getFieldValue(Field field, Class<?> target, Object value) {
        if (null == field) {
            return null;
        }

        String name = "get" + StringUtils.firstUpperCase(field.getName());
        try {
            Method declaredMethod = target.getDeclaredMethod(name);
            if (null != declaredMethod) {
                setAccessible(declaredMethod);
                return declaredMethod.invoke(value);
            }
        } catch (Exception ignore) {
        }

        try {
            Method declaredMethod = target.getDeclaredMethod(field.getName());
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
     *                
     *
     * @param fieldName       
     * @param bean        
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
     *                
     *
     * @param field       
     * @param bean bean
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
     *                
     *
     * @param fieldName       
     * @param type            
     * @param value        
     * @param bean            
     */
    public static void setFieldValue(String fieldName, Object value, Object bean) {
        if (ObjectUtils.isAnyEmpty(fieldName, value, bean)) {
            return;
        }

        setFieldValue(fieldName, bean.getClass(), value, bean);
    }

    /**
     *                                                                               .
     *
     *                                                          
     *
     * @param fieldName                                     
     * @param value                
     * @param bean                         
 * @author CH
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
     *                                              .
     *
     * @param fieldName             
     * @param type                      
     * @param value                
     * @param bean                         
 * @author CH
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
     *                       Field                .
     *
     *       
     * 1.        bean     null                        
     * 2.                 final                    final                      
     * 3.                                  
     *
     * @param field                          null
     * @param value                
     * @param bean                                   null                         
 * @author CH
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
                    Field modifiers = Field.class.getDeclaredField("modifiers");
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
     *                          Field                .
     *
     *                       setXxx()                                                 
     *
     * @param field                          null
     * @param target                      
     * @param value                
     * @param bean                         
 * @author CH
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
            Method declaredMethod = target.getDeclaredMethod(field.getName(), field.getType());
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
     *       
     *
     * @param field       
     * @param value    
     * @param type        
     * @param bean        
     * @param <T>         
     */
    public static <T> void setAllFieldValue(Field field, Object value, Class<T> type, T bean) {
        Class<?> type1 = field.getType();
        value = Converter.convertIfNecessary(value, type1);
        String name = field.getName();
        try {
            Method method = type.getMethod("set" + Character.toUpperCase(name.charAt(0)) + name.substring(1), type1);
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
     *                   
     *
     * @param targetType             
     * @param source        
     * @return
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
     *             
     *
     * @param object       
     * @param field        
     * @return       
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
     * Resolve the given class name into a Class instance. Supports
     * primitives (like "int") and array class names (like "String[]").
     * <p>This is effectively equivalent to the {@code forName}
     * method with the same arguments, with the only difference being
     * the exceptions thrown in case of class loading failure.
     *
     * @param className   the name of the Class
     * @param classLoader the class loader to use
     *                    (may be {@code null}, which indicates the default class loader)
     * @return a class instance for the supplied name
     * @throws IllegalArgumentException if the class name was not resolvable
     *                                  (that is, the class could not be found or the class file could not be loaded)
     * @throws IllegalStateException    if the corresponding class is resolvable but
     *                                  there was a readability mismatch in the inheritance hierarchy of the class
     *                                  (typically a missing dependency declaration in a Jigsaw module definition
     *                                  for a superclass or interface implemented by the class to be loaded here)
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
     *                   
     *
     * @param value        
     * @param target       
     * @return                   
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
     *                   
     *
     * @param value        
     * @param target       
     * @return                   
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
     *                            
     *
     * @param value        
     * @param target       
     * @return                            
     */
    public static <T> T withAssignableFrom(Object value, Class<T> target) {
        if (null == value && isVoid(target)) {
            return null;
        }

        return target.isAssignableFrom(value.getClass()) ? (T) value : null;
    }


    /**
     *                   
     *
     * @param type    
     * @return             
     */
    public static Set<Class<?>> getSuperType(Class<?> type) {
        Set<Class<?>> result = new HashSet<>();
        getSuperType(type, result);
        return result;
    }

    /**
     *                   
     *
     * @param type      
     * @param result       
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
     *                   
     *
     * @param type    
     * @return             
     */
    public static void withInterface(Class<?> type, Consumer<Class<?>> consumer) {
        Set<Class<?>> result = new HashSet<>();
        loopInterfaces(type, result);
        for (Class<?> aClass : result) {
            consumer.accept(aClass);
        }
    }

    /**
     *                   
     *
     * @param type    
     * @return             
     */
    public static Set<Class<?>> getAllInterfaces(Class<?> type) {
        Set<Class<?>> result = new HashSet<>();
        loopInterfaces(type, result);
        return result;
    }

    /**
     *                   
     *
     * @param type      
     * @param result       
     */
    private static void loopInterfaces(Class<?> type, Set<Class<?>> result) {
        Class<?>[] interfaces = type.getInterfaces();
        for (Class<?> anInterface : interfaces) {
            result.add(anInterface);
            loopInterfaces(anInterface, result);
        }
    }

    /**
     *                   
     *
     * @param type    
     * @return             
     */
    public static void withSuperType(Class<?> type, Consumer<Class<?>> consumer) {
        Set<Class<?>> result = new HashSet<>();
        getSuperType(type, result);
        for (Class<?> aClass : result) {
            consumer.accept(aClass);
        }
    }

    /**
     *                               
     *
     * @param classLoader             
     * @return       
     */
    public static List<URL> classLoaderJarRoots(ClassLoader classLoader) {
        if (!(classLoader instanceof URLClassLoader)) {
            return Collections.emptyList();
        }

        //                  
        Enumeration<URL> enumeration = null;

        try {
            enumeration = classLoader.getResources("");
        } catch (IOException e) {
            log.error("", e);
        }
        List<URL> rs = new LinkedList<>();

        while (enumeration.hasMoreElements()) {
            rs.add(enumeration.nextElement());
        }

        URL[] urls = ((URLClassLoader) classLoader).getURLs();
        rs.addAll(Arrays.asList(urls));
        //                                     
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
     *                      ,                   
     *
     * @param args         
     * @param aClass       
     * @return
     */
    public static Object findOnlyOneValue(Collection<Object> args, Class<?> aClass) {
        return findOnlyOneValue(args.toArray(), aClass, 0);
    }

    /**
     *                      ,                   
     *
     * @param args         
     * @param aClass       
     * @param index
     * @return
     */
    public static Object findOnlyOneValue(Object[] args, Class<?> aClass, int index) {
        if (null == args) {
            return null;
        }
        aClass = fromPrimitive(aClass);

        List<Object> tpl = new LinkedList<>();
        for (Object arg : args) {
            if (null == arg || (null != arg && aClass.isAssignableFrom(arg.getClass()))) {
                tpl.add(arg);
            }
        }

        return CollectionUtils.find(tpl, index);
    }

    /**
     *             
     *
     * @param type                 
     * @param annotationType       
     * @return       
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
     *                         
     *
     * @param caller    
     * @return ClassLoader
     */
    public static ClassLoader getCallerClassLoader(Class<?> caller) {
        Preconditions.checkNotNull(caller);
        ClassLoader classLoader = caller.getClassLoader();
        return null == classLoader ? ClassLoader.getSystemClassLoader() : classLoader;
    }

    /**
     *             
     *
     * @param type        
     * @param value       
     * @return             
     */
    public static boolean isAssignableValue(Class<?> type, Object value) {
        return (value != null ? isAssignable(type, value.getClass()) : !type.isPrimitive());
    }

    /**
     *             
     *
     * @param lhsType       
     * @param rhsType       
     * @return             
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
     *                            
     *
     * @param type            
     * @param predicate       
     * @return                      
     */
    public static Map<String, Method> getMethodsByName(Class<?> type, Predicate<Method> predicate) {
        Map<String, Method> rs = new LinkedHashMap<>();
        doWithMethods(type, new SafeConsumer<Method>() {
            @Override
            public void safeAccept(Method method) throws Throwable {
                if (!predicate.test(method)) {
                    return;
                }

                rs.put(method.getName(), method);
                String name = method.getName();
                StringBuilder camel = new StringBuilder();
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
                continue;
            }
            indexValue.addAll(filterType(typeArgument, type).getAll());
        }


        return indexValue;
    }

    /**
     *                   
     *
     * @param declaredClass    
     * @param index                      
     * @param <T>                 
     * @return       
     */
    public static <T> Class<T> resolveGenericType(Class<?> declaredClass, int index) {
        ParameterizedType parameterizedType = (ParameterizedType) declaredClass.getGenericSuperclass();
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        return (Class<T>) actualTypeArguments[index];
    }
    /**
     *                   
     *
     * @param declaredClass    
     * @param <T>                 
     * @return       
     */
    public static <T> Class<T> resolveGenericType(Class<?> declaredClass) {
        return resolveGenericType(declaredClass, 0);
    }

    /**
     *       
     *
     * @param method          
     * @param arguments       
     * @return       
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
     *             
     *
     * @param type       
     * @return       
     */
    public static Set<Class<?>> getAllType(Class<?> type) {
        Set<Class<?>> rs = new LinkedHashSet<>();
        getSuperType(type, rs);

        return rs;
    }

    /**
     *                   
     *
     * @param returnType       
     * @return       
     */
    public static Class<?> getActualType(Class<?> returnType) {
        if (returnType.isArray()) {
            return forName(returnType.getTypeName().replace("[]", ""), returnType.getClassLoader());
        }
        return returnType;
    }

    /**
     *                   
     *
     * @param obj       
     * @return       
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
     *                                        .
     *
     * @param s                 classpath          
     * @return                                                     null
 * @author CH
     * @since 1.0
     */
    public static InputStream getResourceAsStream(String s) {
        return getDefaultClassLoader().getResourceAsStream(s);
    }

    /**
     *                                     .
     *
     *                                              
     * - int.class -> 0
     * - long.class -> 0L
     * - float.class -> 0f
     * - double.class -> 0d
     * - boolean.class -> false
     * - char.class -> ' '                  
     * - short.class -> (short) 0
     * - byte.class -> (byte) 0
     *
     * @param targetType             
     * @return                         
 * @author CH
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
     * promitive -> orm
     *
     * @param cls
     * @return
     */
    public static boolean isPrimitiveWrapper(final Class<?> cls) {
        return null != primitiveToWrapper(cls);
    }

    /**
     * promitive -> orm
     *
     * @param cls
     * @return
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
    public static <T>T newInstance(Constructor<T> declaredConstructor, Object...args) {
        try {
            setAccessible(declaredConstructor);
            return (T) declaredConstructor.newInstance(args);
        } catch (Exception e) {
        }
        return null;
    }

    /**
     *                                                          
     *
     * @param protoMapClass                                      Class<T>   T                   
     * @param args                                                              Object...
     * @return        protoMapClass                                T
     * @throws RuntimeException                                                        InstantiationException   IllegalAccessException   InvocationTargetException     NoSuchMethodException   
     */
    public static <T>T newInstance(Class<T> protoMapClass, Object...args) {
        try {
            Constructor<?>[] declaredConstructors = protoMapClass.getDeclaredConstructors();
            for (Constructor<?> declaredConstructor : declaredConstructors) {
                if(declaredConstructor.getParameterCount() == args.length) {
                    try {
                        setAccessible(declaredConstructor);
                        return (T) declaredConstructor.newInstance(args);
                    } catch (Exception e) {
                    }
                }
            }

            if(args.length == 0) {
                return staticBuilderMethod(protoMapClass);
            }
            return protoMapClass.getDeclaredConstructor().newInstance(args);
        } catch (Exception e) {
            //                      
            throw new RuntimeException(e);
        }
    }

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
     *     Map                               .
     *
     *                                               Map                                     
     *
     * @param item                    Map
     * @param targetType                 Class       
     * @param <T>                            
     * @return                         
 * @author CH
     * @since 1.0
     */
    public static <T> T mapToObject(Map<String, Object> item, Class<T> targetType) {
        T t = forObject(targetType);
        BeanUtils.copyProperties(item, t);
        return t;
    }

    /**
     *             
     * @param target       
     * @param map             
     * @param args             
     * @return       
     * @param <T>
     */
    public static <T> T forObjectOfMap(Class<T> target, Map map, Object... args) {
        T t = forObject(target, args);
        if(null != t) {
            return t;
        }

        Constructor<?>[] declaredConstructors = target.getDeclaredConstructors();
        for (Constructor<?> declaredConstructor : declaredConstructors) {
            Parameter[] parameters = declaredConstructor.getParameters();
            Object[] newArgs = createArgs(parameters, map);
            if(null == newArgs) {
                continue;
            }

            setAccessible(declaredConstructor);
            try {
                return (T) declaredConstructor.newInstance(newArgs);
            } catch (Exception ignore) {
            }
        }
        return null;
    }

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


    static class SetAccessibleAction<T extends AccessibleObject> implements PrivilegedAction<T> {
        private final T obj;

        public SetAccessibleAction(T obj) {
            this.obj = obj;
        }

        @Override
        public T run() {
            setAccessible(obj);
            return obj;
        }

    }
// Short class name
    // ----------------------------------------------------------------------

    /**
     * <p>Gets the class name of the {@code object} without the package name or names.</p>
     *
     * <p>The method looks up the class of the object and then converts the name of the class invoking
     * {@link #getShortClassName(Class)} (see relevant notes there).</p>
     *
     * @param object      the class to get the short name for, may be {@code null}
     * @param valueIfNull the value to return if the object is {@code null}
     * @return the class name of the object without the package name, or {@code valueIfNull}
     * if the argument {@code object} is {@code null}
     */
    public static String getShortClassName(final Object object, final String valueIfNull) {
        if (object == null) {
            return valueIfNull;
        }
        return getShortClassName(object.getClass());
    }

    /**
     * <p>Gets the class name minus the package name from a {@code Class}.</p>
     *
     * <p>This method simply gets the name using {@code Class.getName()} and then calls
     * {@link #getShortClassName(Class)}. See relevant notes there.</p>
     *
     * @param cls the class to get the short name for.
     * @return the class name without the package name or an empty string. If the class
     * is an inner class then the returned value will contain the outer class
     * or classes separated with {@code .} (dot) character.
     */
    public static String getShortClassName(final Class<?> cls) {
        if (cls == null) {
            return CommonConstant.SYMBOL_EMPTY;
        }
        return getShortClassName(cls.getName());
    }

    /**
     * <p>Gets the class name minus the package name from a String.</p>
     *
     * <p>The string passed in is assumed to be a class name - it is not checked. The string has to be formatted the way
     * as the JDK method {@code Class.getName()} returns it, and not the usual way as we write it, for example in import
     * statements, or as it is formatted by {@code Class.getCanonicalName()}.</p>
     *
     * <p>The difference is is significant only in case of classes that are inner classes of some other
     * classes. In this case the separator between the outer and inner class (possibly on multiple hierarchy level) has
     * to be {@code $} (dollar sign) and not {@code .} (dot), as it is returned by {@code Class.getName()}</p>
     *
     * <p>Note that this method is called from the {@link #getShortClassName(Class)} method using the string
     * returned by {@code Class.getName()}.</p>
     *
     * <p>Note that this method differs from {@link #getSimpleName(Class)} in that this will
     * return, for example {@code "Map.Entry"} whilst the {@code java.lang.Class} variant will simply
     * return {@code "Entry"}. In this example the argument {@code className} is the string
     * {@code java.util.Map$Entry} (note the {@code $} sign.</p>
     *
     * @param className the className to get the short name for. It has to be formatted as returned by
     *                  {@code Class.getName()} and not {@code Class.getCanonicalName()}
     * @return the class name of the class without the package name or an empty string. If the class is
     * an inner class then value contains the outer class or classes and the separator is replaced
     * to be {@code .} (dot) character.
     */
    public static String getShortClassName(String className) {
        if (StringUtils.isEmpty(className)) {
            return CommonConstant.SYMBOL_EMPTY;
        }

        final StringBuilder arrayPrefix = new StringBuilder();

        // Handle array encoding
        if (className.startsWith("[")) {
            while (className.charAt(0) == '[') {
                className = className.substring(1);
                arrayPrefix.append("[]");
            }
            // Strip Object type encoding
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

    // Generics type resolution
    // -----------------------------------------------------------------------

    /**
     *                                                          
     *
     *                                                     List&lt;Map&lt;String, Integer&gt;&gt;   
     *
     *          
     * -              List&lt;String&gt;          getGenericType(clazz, 0)        String.class
     * -              Map&lt;String, Integer&gt;          getGenericType(clazz, 0)        String.class
     *
     * @param clazz                            
     * @param index                         0-based                                    
     * @return                                                                       null
     * @throws IllegalArgumentException     index             
 * @author CH
     * @since 2024/12/21
     */
    public static Class<?> getGenericType(Class<?> clazz, int index) {
        if (clazz == null || index < 0) {
            if (index < 0) {
                throw new IllegalArgumentException("                                 ");
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
                    //                                                                            
                    return (Class<?>) ((ParameterizedType) type).getRawType();
                }
            }
            return null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("                        : class={}, index={}", clazz.getName(), index, e);
            }
            return null;
        }
    }

    /**
     *                                                    
     *
     *                                              
     *
     *          
     * -              List&lt;String&gt;          [String.class]
     * -              Map&lt;String, Integer&gt;          [String.class, Integer.class]
     * -                                           
     *
     * @param clazz                            
     * @return                                                                               
 * @author CH
     * @since 2024/12/21
     */
    public static Class<?>[] getGenericTypes(Class<?> clazz) {
        if (clazz == null) {
            return new Class<?>[0];
        }

        //                   
        Type[] cachedTypes = ACTUAL.get(clazz);
        if (cachedTypes != null) {
            return convertToClassArray(cachedTypes);
        }

        try {
            Type[] types = resolveGenericTypes(clazz);

            //             
            if (types != null && types.length > 0) {
                ACTUAL.put(clazz, types);
            } else {
                //                                     
                ACTUAL.put(clazz, new Type[0]);
            }

            return convertToClassArray(types);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("                        : class={}", clazz.getName(), e);
            }
            //                   
            ACTUAL.put(clazz, new Type[0]);
            return new Class<?>[0];
        }
    }

    /**
     *                   
     *
     * @param clazz                
     * @return Type       
     */
    private static Type[] resolveGenericTypes(Class<?> clazz) {
        if (clazz == null) {
            return new Type[0];
        }

        //                                        
        Type[] genericInterfaces = clazz.getGenericInterfaces();
        for (Type genericInterface : genericInterfaces) {
            Type[] types = extractGenericTypes(genericInterface);
            if (types != null && types.length > 0) {
                return types;
            }
        }

        //                            
        Type genericSuperclass = clazz.getGenericSuperclass();
        Type[] types = extractGenericTypes(genericSuperclass);
        if (types != null && types.length > 0) {
            return types;
        }

        return new Type[0];
    }

    /**
     *     Type                      
     *
     * @param type              Type
     * @return                                   ParameterizedType           null
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
     *     Type                 Class       
     *
     * @param types Type       
     * @return Class                                            Object.class       
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
                //                                                                
                classes[i] = (Class<?>) ((ParameterizedType) type).getRawType();
            } else if (type instanceof TypeVariable) {
                //                                   Object.class
                classes[i] = Object.class;
            } else if (type instanceof WildcardType) {
                //                       Object.class
                classes[i] = Object.class;
            } else {
                //                       Object.class
                classes[i] = Object.class;
            }
        }

        return classes;
    }

    /**
     *                         
     *
     *                                                       
     *
 * @author CH
     * @since 2024/12/21
     */
    public static void clearGenericTypeCache() {
        ACTUAL.clear();
        if (log.isDebugEnabled()) {
            log.debug("                           ");
        }
    }

}
