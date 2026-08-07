package com.chua.runtime.spy;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.loader.PluginManager;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 字节码插桩转换器 — 基于 ASM 实现字节码修改。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>支持精确方法插桩：通过 {@link #registerMethod} 指定 className#methodName</li>
 *   <li>在方法入口插入 pre 系插桩点（ENTRY / LOG_PRE / NET_*_PRE / FILE_*_PRE 等）</li>
 *   <li>在方法出口插入 post 系插桩点（EXIT / LOG_POST / NET_CONNECT_POST 等）</li>
 *   <li>在异常出口插入 EXCEPTION 插桩</li>
 *   <li>支持已加载类的 retransform</li>
 * </ul>
 *
 * <p>插桩原理：</p>
 * <p>对于目标方法 {@code void foo(int x)}，插桩后：</p>
 * <pre>
 * void foo(int x) {
 *     RuntimeSpy.onIntercept("目标类", "foo", "方法描述符", "entry");
 *     try {
 *         // 原始方法体
 *         ...
 *         RuntimeSpy.onIntercept("目标类", "foo", "方法描述符", "exit");
 *     } catch (Throwable e) {
 *         RuntimeSpy.onException("目标类", "foo", e);
 *         throw e;
 *     }
 * }
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpyTransformer implements ClassFileTransformer {


    private static final Logger LOG = Logger.getLogger(SpyTransformer.class.getName());
    /**
     * ASM API 版本
     */
    private static final int ASM_API = Opcodes.ASM9;

    /**
     * 默认 Bootstrap 类 — 业务代码拦截入口。
     *
     * <p>使用 {@code com.chua.runtime.agent.Bootstrap} 作为字节码调用的 owner，
     * 该类在 RuntimeAgent 模块定义（位于系统 classloader），
     * 转发到 {@link RuntimeSpy#onIntercept(String, String, String, String) onIntercept} /
     * {@link RuntimeSpy#onException(String, String, Throwable) onException}。
     * 这样 HTTP/Socket 等 bootstrap classloader 加载的 JDK 类
     * 调用插桩代码时也能命中（避免 NoClassDefFoundError）。</p>
     */
    private static final String DEFAULT_SPY_CLASS = "com/chua/runtime/agent/Bootstrap";

    /**
     * Bootstrap 类内部名（可注入，覆盖为 com/chua/runtime/spy/RuntimeSpy 直接调用 RuntimeSpy）。
     */
    private String spyClass = DEFAULT_SPY_CLASS;

    /**
     * onIntercept 方法描述符：固定为 4 个 String 参数返回 void
     */
    private static final String INTERCEPT_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

    /**
     * onException 方法描述符
     */
    private static final String EXCEPTION_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V";

    /**
     * 精确插桩规则：className#methodName -> 插桩点集合
     */
    private final Map<String, Set<InterceptPoint>> methodRules;

    /**
     * 目标类过滤器
     */
    private final List<Pattern> includePatterns;

    /**
     * 排除类过滤器
     */
    private final List<Pattern> excludePatterns;

    /**
     * 已插桩的类集合
     */
    private final Set<String> transformedClasses;

    /**
     * 插件管理器
     */
    private final PluginManager pluginManager;

    /**
     * 创建插桩转换器。
     *
     * @param pluginManager   插件管理器
     * @param includePatterns 包含模式
     * @param excludePatterns 排除模式
     */
    public SpyTransformer(PluginManager pluginManager,
                          List<Pattern> includePatterns,
                          List<Pattern> excludePatterns) {
        this.pluginManager = pluginManager;
        this.methodRules = new ConcurrentHashMap<>();
        this.includePatterns = includePatterns != null ? includePatterns : new ArrayList<>();
        this.excludePatterns = excludePatterns != null ? excludePatterns : new ArrayList<>();
        this.transformedClasses = ConcurrentHashMap.newKeySet();
    }

    /**
     * 注册精确方法插桩规则。
     *
     * @param className  目标类名（内部名，如 "java/net/Socket"）
     * @param methodName 目标方法名
     * @param point      插桩点
     */
    public void registerMethod(String className, String methodName, InterceptPoint point) {
        String key = className + "#" + methodName;
        methodRules.computeIfAbsent(key, k -> EnumSet.noneOf(InterceptPoint.class)).add(point);
        LOG.log(Level.FINE, String.format("注册精确插桩规则: %s -> %s", key, point.getKey()));
    }

    /**
     * 注销精确方法插桩规则。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param point      插桩点
     */
    public void unregisterMethod(String className, String methodName, InterceptPoint point) {
        String key = className + "#" + methodName;
        Set<InterceptPoint> points = methodRules.get(key);
        if (points != null) {
            points.remove(point);
            if (points.isEmpty()) {
                methodRules.remove(key);
            }
        }
        LOG.log(Level.FINE, String.format("注销精确插桩规则: %s -> %s", key, point.getKey()));
    }

    /**
     * 是否已注册精确方法规则。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @return 命中返回 true
     */
    public boolean hasMethodRule(String className, String methodName) {
        return methodRules.containsKey(className + "#" + methodName);
    }

    /**
     * 获取某类的所有精确插桩点。
     *
     * @param className 目标类名
     * @return 插桩点集合，无规则时返回空集合
     */
    private Set<InterceptPoint> rulesOf(String className) {
        Set<InterceptPoint> result = EnumSet.noneOf(InterceptPoint.class);
        for (Map.Entry<String, Set<InterceptPoint>> entry : methodRules.entrySet()) {
            if (entry.getKey().startsWith(className + "#")) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /**
     * 是否有针对该类的任何插桩需求。
     *
     * @param className 目标类名
     * @return 有需求返回 true
     */
    private boolean hasAnyRule(String className) {
        return !rulesOf(className).isEmpty();
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        try {
            if (className == null) {
                return null;
            }
            // 有精确规则的类不跳过，其它类走常规过滤
            boolean exact = hasAnyRule(className);
            if (!exact) {
                if (isSkipClass(className)) {
                    return null;
                }
                if (!isIncludeMatch(className)) {
                    return null;
                }
                if (isExcludeMatch(className)) {
                    return null;
                }
            }
            return transformClass(className, classfileBuffer);

        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("插桩失败: %s", className, e));
            return null;
        }
    }

    /**
     * 执行字节码插桩。
     *
     * @param className       类名（内部名）
     * @param classfileBuffer 原始字节码
     * @return 插桩后的字节码
     */
    private byte[] transformClass(String className, byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES
                | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new SpyClassVisitor(writer, className);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        transformedClasses.add(className);
        return writer.toByteArray();
    }

    /**
     * 是否跳过该类的插桩。
     *
     * @param className 类名
     * @return 跳过返回 true
     */
    private boolean isSkipClass(String className) {
        return className.startsWith("com/chua/runtime/")
                || className.startsWith("java/lang/")
                || className.startsWith("sun/")
                || className.startsWith("jdk/")
                || className.startsWith("org/objectweb/asm")
                || className.startsWith("org/apache/commons/");
    }

    /**
     * 是否匹配包含模式。
     *
     * @param className 类名
     * @return 匹配返回 true
     */
    private boolean isIncludeMatch(String className) {
        if (includePatterns.isEmpty()) {
            return true;
        }
        String canonicalName = className.replace('/', '.');
        for (Pattern p : includePatterns) {
            if (p.matcher(canonicalName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否匹配排除模式。
     *
     * @param className 类名
     * @return 匹配返回 true
     */
    private boolean isExcludeMatch(String className) {
        String canonicalName = className.replace('/', '.');
        for (Pattern p : excludePatterns) {
            if (p.matcher(canonicalName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对已加载类执行 retransform。
     *
     * @param inst Instrumentation 实例
     * @throws Exception 重变换异常
     */
    public void retransformLoadedClasses(Instrumentation inst) throws Exception {
        List<Class<?>> toRetransform = new ArrayList<>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            String name = clazz.getName().replace('.', '/');
            boolean exact = hasAnyRule(name);
            if (exact || (!isSkipClass(name) && isIncludeMatch(name) && !isExcludeMatch(name))) {
                toRetransform.add(clazz);
            }
        }
        if (!toRetransform.isEmpty()) {
            LOG.log(Level.INFO, String.format("对 %s 个已加载类执行 retransform", toRetransform.size()));
            inst.retransformClasses(toRetransform.toArray(new Class[0]));
        }
    }

    /**
     * 插桩 ClassVisitor — 在每个方法中插入插桩代码。
     */
    private class SpyClassVisitor extends ClassVisitor {

        /**
         * 目标类名（内部名）
         */
        private final String targetClass;

        /**
         * 目标类的精确插桩点集合
         */
        private final Set<InterceptPoint> classRules;

        SpyClassVisitor(ClassVisitor cv, String targetClass) {
            super(ASM_API, cv);
            this.targetClass = targetClass;
            this.classRules = rulesOf(targetClass);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }
            // 跳过构造器与静态块
            if (name.equals("<init>") || name.equals("<clinit>")) {
                return mv;
            }
            Set<InterceptPoint> points = classRules;
            boolean exact = !points.isEmpty();
            // 无精确规则时仅插桩 public/protected 方法
            if (!exact && !((access & Opcodes.ACC_PUBLIC) != 0 || (access & Opcodes.ACC_PROTECTED) != 0)) {
                return mv;
            }
            // 没有插桩点需求则跳过
            if (points.isEmpty()) {
                return mv;
            }
            return new SpyMethodVisitor(ASM_API, mv, targetClass, name, descriptor, points, exact, spyClass);
        }
    }

    /**
     * 插桩 MethodVisitor — 在方法入口/出口/异常出口插入字节码。
     */
    private static class SpyMethodVisitor extends AdviceAdapter {

        /**
         * 目标类名（内部名）
         */
        private final String targetClass;

        /**
         * 方法名
         */
        private final String methodName;

        /**
         * 方法描述符
         */
        private final String methodDescriptor;

        /**
         * 本方法需要插入的插桩点
         */
        private final Set<InterceptPoint> points;

        /**
         * 是否精确规则命中
         */
        private final boolean exact;

        /**
         * Bootstrap 类内部名（RuntimeSpy 或 com.chua.runtime.agent.Bootstrap）。
         */
        private final String spyClass;

        /**
         * 方法体起始标签（try 范围起点）
         */
        private Label tryStart;

        /**
         * 方法体结束标签（try 范围终点）
         */
        private Label tryEnd;

        /**
         * 异常处理器标签
         */
        private Label exceptionLabel;

        SpyMethodVisitor(int api, MethodVisitor mv, String targetClass,
                         String methodName, String descriptor,
                         Set<InterceptPoint> points, boolean exact, String spyClass) {
            super(api, mv, 0, methodName, descriptor);
            this.targetClass = targetClass;
            this.methodName = methodName;
            this.methodDescriptor = descriptor;
            this.points = points;
            this.exact = exact;
            this.spyClass = spyClass;
            this.tryStart = new Label();
            this.tryEnd = new Label();
            this.exceptionLabel = new Label();
        }

        @Override
        protected void onMethodEnter() {
            // 标记 try 范围起点（异常插桩需要）
            if (points.contains(InterceptPoint.EXCEPTION)) {
                mv.visitLabel(tryStart);
            }
            // 插入所有 pre 系插桩点（方法入口）
            for (InterceptPoint point : points) {
                if (isPrePoint(point)) {
                    insertInterceptCall(point);
                }
            }
        }

        @Override
        protected void onMethodExit(int opcode) {
            // 插入所有 post 系插桩点（方法正常出口）
            for (InterceptPoint point : points) {
                if (isPostPoint(point)) {
                    insertInterceptCall(point);
                }
            }
            // 标记 try 范围终点（异常插桩需要）
            if (points.contains(InterceptPoint.EXCEPTION)) {
                mv.visitLabel(tryEnd);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // 异常处理：插入 EXCEPTION 插桩后重新抛出
            if (points.contains(InterceptPoint.EXCEPTION)) {
                mv.visitTryCatchBlock(tryStart, tryEnd, exceptionLabel, null);
                mv.visitLabel(exceptionLabel);
                // 栈顶为异常对象，压入类名与方法名后 DUP 一份用于插桩
                mv.visitLdcInsn(targetClass);
                mv.visitLdcInsn(methodName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, spyClass, "onException",
                        EXCEPTION_DESC, false);
                mv.visitInsn(Opcodes.ATHROW);
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        /**
         * 插入单个插桩点调用。
         *
         * @param point 插桩点
         */
        private void insertInterceptCall(InterceptPoint point) {
            // 压入 className
            mv.visitLdcInsn(targetClass);
            // 压入 methodName
            mv.visitLdcInsn(methodName);
            // 压入 methodDescriptor
            mv.visitLdcInsn(methodDescriptor);
            // 压入 pointKey
            mv.visitLdcInsn(point.getKey());
            // 调用 Bootstrap.onIntercept
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, spyClass, "onIntercept",
                    INTERCEPT_DESC, false);
        }

        /**
         * 是否为 pre 系插桩点（方法入口插入）。
         *
         * @param point 插桩点
         * @return 是返回 true
         */
        private boolean isPrePoint(InterceptPoint point) {
            return switch (point) {
                case ENTRY, LOG_PRE, NET_CONNECT_PRE, NET_READ_PRE, NET_WRITE_PRE,
                        FILE_OPEN_PRE, FILE_READ_PRE, HTTP_REQUEST_PRE, DB_SQL_PRE,
                        THREAD_CREATE_PRE, CLASS_LOAD_PRE -> true;
                default -> false;
            };
        }

        /**
         * 是否为 post 系插桩点（方法出口插入）。
         *
         * @param point 插桩点
         * @return 是返回 true
         */
        private boolean isPostPoint(InterceptPoint point) {
            return switch (point) {
                case EXIT, LOG_POST, NET_CONNECT_POST, FILE_OPEN_POST,
                        HTTP_RESPONSE_POST, DB_SQL_POST -> true;
                default -> false;
            };
        }
    }

    /**
     * 获取已插桩的类数量。
     *
     * @return 数量
     */
    public int getTransformedClassCount() {
        return transformedClasses.size();
    }

    /**
     * 设置 Bootstrap 类内部名（用于字节码 INVOKESTATIC 目标）。
     *
     * <p>调用方负责 Bootstrap 类的实际存在 —— 默认是
     * "com/chua/runtime/agent/Bootstrap"（RuntimeAgent 模块，system classloader 加载）。
     * 设为 "com/chua/runtime/spy/RuntimeSpy" 则直接调用 RuntimeSpy（测试用）。</p>
     *
     * @param spyClass Bootstrap 类内部名（含斜杠分隔符）
     */
    public void setSpyClass(String spyClass) {
        if (spyClass != null && !spyClass.isEmpty()) {
            this.spyClass = spyClass;
        }
    }

    /**
     * 获取当前 Bootstrap 类内部名。
     *
     * @return Bootstrap 类内部名
     */
    public String getSpyClass() {
        return spyClass;
    }

    /**
     * 获取当前精确规则数量。
     *
     * @return 数量
     */
    public int getMethodRuleCount() {
        return methodRules.size();
    }

    /**
     * 重置状态。
     */
    public void reset() {
        transformedClasses.clear();
        methodRules.clear();
    }
}
