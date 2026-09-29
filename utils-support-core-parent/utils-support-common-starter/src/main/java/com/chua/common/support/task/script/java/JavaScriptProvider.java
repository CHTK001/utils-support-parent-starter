package com.chua.common.support.task.script.java;

import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.lang.compile.JdkCompiler;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.script.ScriptHost;
import com.chua.common.support.task.script.ScriptProvider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

/**
 * Java 代码片段脚本引擎。
 *
 * <p>SPI 类型：{@code java}。把一段带类定义的 Java 源码当作「片段」：加载阶段用
 * {@link JdkCompiler} 编译成类并缓存编译产物，执行阶段复用同一编译产物实例化并调用
 * 入口方法。这样「保存时校验可编译」与「运行时执行」共享同一次编译，不重复编译。</p>
 *
 * <p>本类位于 {@code ScriptProvider} 所在包的子包中，仓库自带的同包 SPI 扫描
 * （{@code ServiceProvider}）可直接发现；Spring 宿主下则由
 * {@code META-INF/spring.factories} + {@code SpringFactoriesLoader} 发现。
 * 本类不引用任何 Spring 类型，保持 {@code utils-support-common-starter} 纯 JDK 工具层。</p>
 *
 * <p>入口方法三级回退、实参绑定规则见 {@link #resolveEntry(Class)} 与
 * {@link #bindArguments(Method, Object)}。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("java")
public class JavaScriptProvider implements ScriptProvider {

    /**
     * 编译产物缓存：脚本路径 -> 编译结果。
     *
     * <p>静态缓存保证同一进程内「保存时校验编译」与「执行」复用同一个编译产物对象，
     * 不重复编译。</p>
     */
    private static final Map<Path, Compiled> CACHE = new ConcurrentHashMap<>();

    /**
     * 入口方法上宿主 {@code @Job} 注解的简单名。
     *
     * <p>本模块是纯 JDK 工具层，引不进 job-starter 的 {@code @Job}，因此只按注解
     * 简单名匹配。这是刻意的解耦：任何简单名为 {@code Job} 的注解都会被视为入口标记，
     * 不要求注解来自某个具体框架。</p>
     */
    private static final String JOB_ANNOTATION_SIMPLE_NAME = "Job";

    /**
     * 约定入口方法名。
     */
    private static final String ENTRY_METHOD_NAME = "execute";

    /**
     * 上下文中的脚本宿主键。
     */
    private static final String SCRIPT_HOST_KEY = "scriptHost";

    /**
     * @return 引擎名称 {@code java}
     */
    @Override
    public String engineName() {
        return "java";
    }

    /**
     * 加载（编译并缓存）Java 代码片段。
     *
     * <p>这是「保存时校验可编译」的入口：读取源码后解析全限定类名，再用
     * {@link JdkCompiler} 编译。编译失败时 {@link JdkCompiler} 抛出携带 javac 诊断的
     * {@link IllegalStateException}，此处原样向上抛，不做任何吞并。编译成功后把类与已解析的
     * 入口方法一起缓存，后续执行复用同一编译产物，不重复编译。</p>
     *
     * @param scriptPath 脚本文件路径
     * @return 加载成功返回 true；同一路径已在缓存中时直接返回 true，不重复编译
     */
    @Override
    public boolean loadScript(Path scriptPath) {
        if (scriptPath == null) {
            throw new IllegalArgumentException("Java 片段路径不能为 null");
        }
        if (CACHE.containsKey(scriptPath)) {
            return true;
        }
        String source;
        try {
            source = Files.readString(scriptPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Java 片段读取失败: " + scriptPath + ", cause: " + e.getMessage(), e);
        }
        String className = resolveClassName(source);
        Class<?> type;
        try {
            type = new JdkCompiler().doCompile(className, source);
        } catch (IllegalStateException e) {
            // JdkCompiler 的编译诊断就在异常消息里，原样向上抛
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Java 片段编译失败: " + scriptPath + ", cause: " + e.getMessage(), e);
        }
        if (type == null) {
            throw new IllegalStateException("Java 片段编译结果为空: " + scriptPath);
        }
        Method entry = resolveEntry(type);
        CACHE.put(scriptPath, new Compiled(className, type, entry, source));
        return true;
    }

    /**
     * 执行 Java 代码片段：复用缓存中的编译产物。
     *
     * <p>缓存未命中时先调用 {@link #loadScript(Path)}，取到的仍是同一个编译产物对象，
     * 不重复编译。随后无参实例化、按上下文做宿主装配、绑定实参并调用入口方法。</p>
     *
     * @param scriptPath 脚本路径
     * @param context    执行上下文（可为 Map / 普通对象 / null）
     * @return 入口方法返回值
     */
    @Override
    public Object executeScript(Path scriptPath, Object context) {
        Compiled compiled = CACHE.get(scriptPath);
        if (compiled == null) {
            loadScript(scriptPath);
            compiled = CACHE.get(scriptPath);
        }
        if (compiled == null) {
            throw new IllegalStateException("Java 片段未加载: " + scriptPath);
        }
        Object instance = ReflectUtils.instantiate(compiled.type());
        if (instance == null) {
            throw new IllegalStateException("Java 片段无法实例化（需要一个可访问的无参构造器）: " + compiled.className());
        }
        injectHost(instance, context);
        Object[] args = bindArguments(compiled.entry(), context);
        try {
            return ReflectUtils.invokeProxy(instance, compiled.entry(), args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Java 片段执行失败: " + scriptPath + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 移出缓存。
     *
     * @param scriptPath 脚本路径
     */
    @Override
    public void unloadScript(Path scriptPath) {
        CACHE.remove(scriptPath);
    }

    /**
     * 判断脚本是否已加载。
     *
     * @param scriptPath 脚本路径
     * @return 缓存命中返回 true
     */
    @Override
    public boolean isLoaded(Path scriptPath) {
        return CACHE.containsKey(scriptPath);
    }

    /**
     * 从源码解析全限定类名。
     *
     * <p>复用 {@link Compiler} 的 {@code PACKAGE_PATTERN} 与 {@code CLASS_PATTERN}，
     * 不另写正则；无 package 声明时只返回简单类名。</p>
     *
     * @param source Java 源码
     * @return 全限定类名
     * @throws IllegalArgumentException 源码中没有 {@code class} 定义时
     */
    private static String resolveClassName(String source) {
        String code = source.trim();
        Matcher packageMatcher = Compiler.PACKAGE_PATTERN.matcher(code);
        String pkg = packageMatcher.find() ? packageMatcher.group(1) : "";
        Matcher classMatcher = Compiler.CLASS_PATTERN.matcher(code);
        if (!classMatcher.find()) {
            throw new IllegalArgumentException("Java 片段缺少类定义: \n" + code);
        }
        String simpleName = classMatcher.group(1);
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }

    /**
     * 解析入口方法，三级回退。
     *
     * <p>按顺序尝试：</p>
     * <ol>
     *   <li>带简单名为 {@code Job} 注解的方法（见 {@link #JOB_ANNOTATION_SIMPLE_NAME} 的解耦说明）</li>
     *   <li>名为 {@code execute} 的方法</li>
     *   <li>该类唯一的 public 方法（不含继承自 {@link Object} 的方法与编译器合成方法）</li>
     * </ol>
     *
     * @param type 编译后的类
     * @return 入口方法
     * @throws IllegalStateException 三级回退都无法唯一确定入口时
     */
    private static Method resolveEntry(Class<?> type) {
        List<Method> methods = ReflectUtils.getPublicMethods(type);
        for (Method method : methods) {
            if (hasJobAnnotation(method)) {
                return method;
            }
        }
        for (Method method : methods) {
            if (ENTRY_METHOD_NAME.equals(method.getName())) {
                return method;
            }
        }
        List<Method> candidates = new ArrayList<>();
        for (Method method : methods) {
            if (method.getDeclaringClass() == Object.class || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            candidates.add(method);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalStateException("无法确定 Java 片段入口方法: " + type.getName()
                + "，请给入口方法加 @Job 注解、命名为 " + ENTRY_METHOD_NAME
                + "，或保证该类只有一个 public 方法；当前 public 方法数 = " + candidates.size());
    }

    /**
     * 判断方法是否带有简单名为 {@code Job} 的注解。
     *
     * @param method 目标方法
     * @return 命中返回 true
     */
    private static boolean hasJobAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (JOB_ANNOTATION_SIMPLE_NAME.equals(annotation.annotationType().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为入口方法绑定实参，每个形参独立解析。
     *
     * <p>解析顺序：</p>
     * <ol>
     *   <li>{@code context} 是 Map 时按形参名找同名键</li>
     *   <li>取第一个「运行时类型可赋给该形参类型」的候选值（Map 的值，或 context 本身）</li>
     *   <li>仍未命中时传 null</li>
     * </ol>
     *
     * @param entry   入口方法
     * @param context 执行上下文
     * @return 与方法形参一一对应的实参数组；零参方法返回空数组
     */
    private static Object[] bindArguments(Method entry, Object context) {
        Class<?>[] parameterTypes = entry.getParameterTypes();
        if (parameterTypes.length == 0) {
            return new Object[0];
        }
        Parameter[] parameters = entry.getParameters();
        Map<?, ?> contextMap = context instanceof Map ? (Map<?, ?>) context : null;
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = resolveArgument(parameters, i, parameterTypes[i], context, contextMap);
        }
        return args;
    }

    /**
     * 解析单个形参的实参。
     *
     * @param parameters    入口方法的形参数组
     * @param index         形参下标
     * @param parameterType 形参类型
     * @param context       执行上下文
     * @param contextMap    context 为 Map 时的视图，否则为 null
     * @return 绑定的实参，未命中返回 null
     */
    private static Object resolveArgument(Parameter[] parameters, int index, Class<?> parameterType,
                                          Object context, Map<?, ?> contextMap) {
        if (contextMap != null) {
            String name = parameters[index].getName();
            if (contextMap.containsKey(name)) {
                return contextMap.get(name);
            }
            for (Object value : contextMap.values()) {
                if (isAssignable(parameterType, value)) {
                    return value;
                }
            }
        }
        return isAssignable(parameterType, context) ? context : null;
    }

    /**
     * 判断值能否赋给目标形参类型（自动处理基本类型装箱）。
     *
     * @param parameterType 形参类型
     * @param value         候选值，允许为 null
     * @return 可赋值返回 true；value 为 null 时返回 false
     */
    private static boolean isAssignable(Class<?> parameterType, Object value) {
        if (value == null) {
            return false;
        }
        Class<?> target = parameterType.isPrimitive() ? wrap(parameterType) : parameterType;
        return target.isInstance(value);
    }

    /**
     * 基本类型转包装类型。
     *
     * @param type 类型
     * @return 对应的包装类型；非基本类型原样返回
     */
    private static Class<?> wrap(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    /**
     * 若上下文携带脚本宿主，则对片段实例做宿主装配。
     *
     * <p>Spring 宿主下这一步让片段字段的 {@code @Autowired} 生效；非 Spring 宿主可不提供。</p>
     *
     * @param instance 片段实例
     * @param context  执行上下文
     */
    private static void injectHost(Object instance, Object context) {
        if (!(context instanceof Map)) {
            return;
        }
        Object host = ((Map<?, ?>) context).get(SCRIPT_HOST_KEY);
        if (host instanceof ScriptHost) {
            ((ScriptHost) host).inject(instance);
        }
    }

    /**
     * 编译产物：类名、Class、入口方法与源码。
     *
     * <p>{@link Class} / {@link Method} 本身不可变，源码是 String，因此不做防御性拷贝，
     * 只对语义必填组件做空值校验。</p>
     *
     * @param className 全限定类名
     * @param type      编译后的类
     * @param entry     已解析的入口方法
     * @param source    编译所用源码
     */
    private record Compiled(String className, Class<?> type, Method entry, String source) {

        /**
         * 紧凑构造器：四个组件均为语义必填。
         */
        Compiled {
            Objects.requireNonNull(className, "className 不能为 null");
            Objects.requireNonNull(type, "type 不能为 null");
            Objects.requireNonNull(entry, "entry 不能为 null");
            Objects.requireNonNull(source, "source 不能为 null");
        }
    }
}
