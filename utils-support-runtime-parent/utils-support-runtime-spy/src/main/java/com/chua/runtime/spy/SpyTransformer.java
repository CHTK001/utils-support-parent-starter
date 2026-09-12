package com.chua.runtime.spy;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
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


    /**
      * 日志
     */
    private static final Logger LOG = Logger.getLogger(SpyTransformer.class.getName());
    /**
     * ASM API 版本
     */
    private static final int ASM_API = Opcodes.ASM9;

    /**
     * 默认 Bootstrap 类 — 业务代码拦截入口。
     *
     * <p>使用 {@code com.chua.runtime.agent.Bootstrap} 作为字节码调用的 owner，
      * 该类在 runtime智能体 模块定义（位于系统 classloader），
     * 转发到 {@link RuntimeSpy#onIntercept(String, String, String, String) onIntercept} /
     * {@link RuntimeSpy#onException(String, String, Throwable) onException}。
      * 这样 HTTP/套接字 等 bootstrap classloader 加载的 JDK 类
      * 调用插桩代码时也能命中（避免 no类deffound错误）。</p>
     */
    private static final String DEFAULT_SPY_CLASS = "com/chua/runtime/agent/Bootstrap";

    /**
      * Bootstrap 类内部名（可注入，覆盖为 com/chua/runtime/spy/runtimespy 直接调用 runtimespy）。
     */
    private String spyClass = DEFAULT_SPY_CLASS;

    /**
      * onintercept 方法描述符：5 个参数（类名称, 方法名称, descriptor, point键, thisref）。
     *
     * <p>thisRef 在所有插桩点（ENTRY / EXIT / LOG_PRE / NET_CONNECT_PRE / ...）都压入：
      * 对于静态方法即为 空，由 Bootstrap 自动用 ACC_静态 鉴别。
      * 这样 处理器 在收到 ctx 时可以直接通过 {@code ctx.getUserData()} 拿到受拦截的实例
      * （套接字、httpurlconnection、文件输入流 等）。</p>
     */
    private static final String INTERCEPT_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

    /**
      * on异常 方法描述符
     */
    private static final String EXCEPTION_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V";

    /**
      * 精确插桩规则：类名称#方法名称 -> 插桩点集合
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
     * @param className  目标类名（内部名，如 "Java/net/套接字"）
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
      * 获取某类某方法的精确插桩点 — 关键修复：避免 类rules 全集污染未注册的方法。
     *
     * @param className  目标类名
     * @param methodName 方法名
     * @return 仅包含针对该方法的插桩点；空表示不需要插桩
     */
    private Set<InterceptPoint> pointsOf(String className, String methodName) {
        Set<InterceptPoint> rule = methodRules.get(className + "#" + methodName);
        return rule != null ? rule : EnumSet.noneOf(InterceptPoint.class);
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
    /**
      * 转换
     * @param loader loader
     * @param className 类名称
     * @param classBeingRedefined 类存在redefined
     * @param protectionDomain protectiondomain
     * @param classfileBuffer classfile缓冲
     */
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
            return transformClass(className, classfileBuffer, loader);

        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("插桩失败: %s — %s: %s", className, e.getClass().getName(), e.getMessage()), e);
            return null;
        }
    }

    /**
     * 执行字节码插桩。
     *
     * @param className       类名（内部名）
     * @param classfileBuffer 原始字节码
     * @param loader          目标类的类加载器（用于 获取通用父类 解析引用类型）
     * @return 插桩后的字节码
     */
    private byte[] transformClass(String className, byte[] classfileBuffer, ClassLoader loader) {
        ClassReader reader = new ClassReader(classfileBuffer);
 // COMPUTE_帧 + EXPAND_帧 是 advice适配器（继承 本地变量排序）的标准配置
 // 自定义 类writer 覆盖 获取通用父类：用目标类自身的 加载 加载引用类型，
 // 否则三方库（如 MySQL-connector）方法签名中引用尚未加载的异常类型时抛 类型notpresent异常
        ClassWriter writer = new ResolvingClassWriter(reader, loader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new SpyClassVisitor(writer, className);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        transformedClasses.add(className);
        return writer.toByteArray();
    }

    /**
      * 可解析引用类型的 类writer — 解决三方库类插桩时 获取通用父类 无法加载异常类型的问题。
     *
     * <p>ASM 的 {@link ClassWriter#getCommonSuperClass(String, String)} 默认使用线程上下文类加载器
      * 或调用方类加载器加载类；当被插桩类（如 MySQL-connector 的 connectionimpl）的方法签名引用了
     * 尚未加载的异常/返回值类型时，加载会失败并抛出 {@link TypeNotPresentException}，
      * 导致整个插桩失败（转换 返回 空）。本类改用目标类自身的类加载器解析，
     * 从而在加载该三方类时能正确解析其引用的所有类型。</p>
     */
    private static final class ResolvingClassWriter extends ClassWriter {

        /**
         * 目标类加载器（用于解析被插桩类引用的类型）
         */
        private final ClassLoader targetLoader;

        /**
         * 构造器。
         *
         * @param reader       类读取器
         * @param targetLoader 目标类加载器（可为 空 表示 bootstrap）
         * @param flags        标志位
         */
        ResolvingClassWriter(ClassReader reader, ClassLoader targetLoader, int flags) {
            super(reader, flags);
            this.targetLoader = targetLoader;
        }

        @Override
        /** 获取通用父类 */
        protected String getCommonSuperClass(String type1, String type2) {
            ClassLoader loader = targetLoader != null
                    ? targetLoader : ClassLoader.getSystemClassLoader();
            Class<?> c = loadClass(type1, loader);
            Class<?> d = loadClass(type2, loader);
            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            }
            do {
                c = c.getSuperclass();
            } while (c != null && !c.isAssignableFrom(d));
            if (c == null) {
                return "java/lang/Object";
            }
            return c.getName().replace('.', '/');
        }

        /**
          * 加载类（空 时回退 对象）。
         *
         * @param type 内部名
         * @param loader 类加载器
         * @return Class 实例
         */
        private static Class<?> loadClass(String type, ClassLoader loader) {
            try {
                return ReflectUtils.forName(type.replace('/', '.'), loader);
            } catch (Throwable e) {
                // 引用类型不可加载时，回退到最安全的上界
                return Object.class;
            }
        }
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
      * 插桩 类visitor — 在每个方法中插入插桩代码。
     * @author CH
     * @since 4.0.0
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
        /**
          * visit方法
         * @param access access
         * @param name 名称
         * @param descriptor descriptor
         * @param signature 签名
         * @param exceptions 异常
         */
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }
 // 关键修复：从 类rules 中取出**仅匹配当前方法**的插桩点
 // 否则 类rules 包含该类任意方法注册的规则，会污染其他无关方法
            Set<InterceptPoint> points = pointsOf(targetClass, name);
            boolean exact = !points.isEmpty();
            // 跳过构造器与静态块（除非有针对 <init>/<clinit> 的精确插桩规则）
            boolean isInitLike = name.equals("<init>") || name.equals("<clinit>");
            if (isInitLike && !exact) {
                return mv;
            }
 // 无精确规则时仅插桩 公共/受保护 方法
            if (!exact && !((access & Opcodes.ACC_PUBLIC) != 0 || (access & Opcodes.ACC_PROTECTED) != 0)) {
                return mv;
            }
            // 没有插桩点需求则跳过
            if (points.isEmpty()) {
                return mv;
            }
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            return new SpyMethodVisitor(ASM_API, mv, access, targetClass, name, descriptor, points, exact, spyClass, isStatic);
        }
    }

    /**
      * 插桩 方法visitor — 在方法入口/出口/异常出口插入字节码。
     *
     * <p>继承 {@link AdviceAdapter} 利用其自动管理 local 索引重写 + frame 合并逻辑，
      * 避免手工维护 stack映射table 带来的复杂性。</p>
     * @author CH
     * @since 4.0.0
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
         * 当前方法是否为静态。
         */
        private final boolean isStatic;

        /**
          * Bootstrap 类内部名（runtimespy 或 com.chua.runtime.智能体.Bootstrap）。
         */
        private final String spyClass;

        /**
          * 方法体起始标签（尝试 范围起点）
         */
        private Label tryStart;

        /**
          * 方法体结束标签（尝试 范围终点）
         */
        private Label tryEnd;

        /**
         * 异常处理器标签
         */
        private Label exceptionLabel;

        SpyMethodVisitor(int api, MethodVisitor mv, int access, String targetClass,
                         String methodName, String descriptor,
                         Set<InterceptPoint> points, boolean exact, String spyClass,
                         boolean isStatic) {
            super(api, mv, access, methodName, descriptor);
            this.targetClass = targetClass;
            this.methodName = methodName;
            this.methodDescriptor = descriptor;
            this.points = points;
            this.exact = exact;
            this.spyClass = spyClass;
            this.isStatic = isStatic;
        }

        @Override
        /** on方法enter */
        protected void onMethodEnter() {
 // 标记 尝试 范围起点（异常插桩需要）
            if (points.contains(InterceptPoint.EXCEPTION)) {
                tryStart = new Label();
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
        /** on方法exit */
        protected void onMethodExit(int opcode) {
 // 非 Void Linux Linux 返回值方法：先把返回值存入临时 本地，插入插桩调用后再恢复，
 // 否则 long/double 等两槽返回值会与 onintercept 参数压栈冲突导致 验证错误
            boolean hasValue = isValueReturn(opcode);
            int returnSlot = -1;
            Type returnType = null;
            if (hasValue) {
                returnType = Type.getReturnType(methodDescriptor);
                returnSlot = newLocal(returnType);
                mv.visitVarInsn(returnType.getOpcode(Opcodes.ISTORE), returnSlot);
            }
 // 插入所有 post 系插桩点（方法正常出口；异常路径由 异常 独立处理）
            for (InterceptPoint point : points) {
                if (isPostPoint(point)) {
                    insertInterceptCall(point);
                }
            }
            // 恢复返回值
            if (hasValue && returnType != null && returnSlot >= 0) {
                mv.visitVarInsn(returnType.getOpcode(Opcodes.ILOAD), returnSlot);
            }
 // 标记 尝试 范围终点（异常插桩需要）
            if (points.contains(InterceptPoint.EXCEPTION)) {
                tryEnd = new Label();
                mv.visitLabel(tryEnd);
            }
        }

        /**
         * 判断方法出口是否为普通返回（有操作数返回值）。
         *
         * <p>排除 RETURN（void）与 ATHROW（异常路径，栈顶是异常对象而非返回值，由 EXCEPTION 独立处理）。</p>
         *
         * @param opcode 出口操作码
         * @return 是值返回返回 true
         */
        private boolean isValueReturn(int opcode) {
            return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                    || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                    || opcode == Opcodes.ARETURN;
        }

        @Override
        /** visit最大 */
        public void visitMaxs(int maxStack, int maxLocals) {
 // 异常处理：插入 异常 插桩后重新抛出
            if (points.contains(InterceptPoint.EXCEPTION)) {
                if (tryEnd == null) {
                    tryEnd = new Label();
                    mv.visitLabel(tryEnd);
                }
                exceptionLabel = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, exceptionLabel, null);
                mv.visitLabel(exceptionLabel);
                // 栈顶为异常对象 — 调用 onException(className, methodName, throwable) V
                // onException 签名 (String, String, Throwable) V — 入参顺序从栈顶往下
 // 因此先 DUP 抛出，再 LDC 2 个 字符串，让 抛出 落在入参底部
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(targetClass);
                mv.visitLdcInsn(methodName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, spyClass, "onException",
                        EXCEPTION_DESC, false);
                mv.visitInsn(Opcodes.ATHROW);
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        /**
         * 插入单个插桩点调用。
         *
         * <p>签名：{@code static void onIntercept(Object thisRef, String className, String methodName, String descriptor, String pointKey)}</p>
         * <p>静态方法传入 null 作为 thisRef。</p>
         *
         * @param point 插桩点
         */
        private void insertInterceptCall(InterceptPoint point) {
 // 压入 thisref（静态方法传 空；实例方法传 ALOAD 0）
            if (isStatic) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
            }
 // 压入 类名称
            mv.visitLdcInsn(targetClass);
 // 压入 方法名称
            mv.visitLdcInsn(methodName);
 // 压入 方法descriptor
            mv.visitLdcInsn(methodDescriptor);
 // 压入 point键
            mv.visitLdcInsn(point.getKey());
 // 调用 Bootstrap.onintercept
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
      * "com/chua/runtime/智能体/Bootstrap"（runtime智能体 模块，系统 classloader 加载）。
      * 设为 "com/chua/runtime/spy/runtimespy" 则直接调用 runtimespy（测试用）。</p>
     *
     * @param spyClass Bootstrap 类内部名（含斜杠分隔符）
     */
    public void setSpyClass(String spyClass) {
        if (StringUtils.isNotEmpty(spyClass)) {
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
