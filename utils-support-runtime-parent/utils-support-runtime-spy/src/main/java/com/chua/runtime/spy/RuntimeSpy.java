package com.chua.runtime.spy;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.runtime.plugin.InterceptPoint;
import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 插桩运行时入口 — 被 ASM 字节码修改插入到目标方法中。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>作为 ASM 字节码插桩的静态入口，被 SpyTransformer 插入</li>
 *   <li>维护 Handler 注册表，根据类名/方法名/插桩点路由事件到对应 Handler</li>
 *   <li>维护线程局部上下文（TraceContext）</li>
 * </ul>
 *
 * <p>插桩流程：</p>
 * <pre>
 * 1. Handler.start() 调用 RuntimeSpy.registerInterceptor()
 * 2. RuntimeSpy 将拦截规则传递给 SpyTransformer
 * 3. SpyTransformer 在类加载时插入字节码: RuntimeSpy.onIntercept()
 * 4. 目标方法执行时触发 RuntimeSpy.onIntercept()
 * 5. RuntimeSpy 查找匹配的 Handler 并调用 onIntercept()
 * </pre>
 *
 * <p>插入的字节码：</p>
 * <pre>
 * LDC "org/slf4j/Logger"           // className
 * LDC "info"                        // methodName
 * LDC "(Ljava/lang/String;)V"       // descriptor
 * LDC "log_pre"                     // pointKey
 * INVOKESTATIC RuntimeSpy.onIntercept
 *
 *   ... 原始方法体 ...
 *
 * LDC "org/slf4j/Logger"           // className
 * LDC "info"                        // methodName
 * LDC "(Ljava/lang/String;)V"       // descriptor
 * LDC "log_post"                    // pointKey
 * INVOKESTATIC RuntimeSpy.onIntercept
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RuntimeSpy {

    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(RuntimeSpy.class.getName());
    /**
     * 拦截点匹配键分隔符（className#methodName#pointKey）
     */
    private static final String KEY_SEPARATOR = "#";

    /**
     * traceId / spanId 标识长度（16 字符）
     */
    private static final int ID_LENGTH = 16;

    /**
     * 追踪栈最大深度 — 防止 AOP 死循环或意外递归导致栈帧累积到 OOM
     */
    private static final int MAX_TRACE_DEPTH = 256;

    /**
     * 跨线程追踪上下文最大条目数 — 防止海量 key 导致内存爆炸
     */
    private static final int MAX_CROSS_THREAD_TRACES = 10_000;

    /**
     * 跨线程追踪条目 TTL（毫秒） — 防止未配对 restoreByKey 导致内存泄漏
     */
    private static final long CROSS_THREAD_TRACE_TTL_MS = 60L * 60 * 1000;

    /**
     * 插桩上下文（仅 ENTRY/EXIT 之间有效）
     */
    private static final ThreadLocal<SpyContext> CONTEXT = new ThreadLocal<>();

    /**
     * ENTRY 时记录的 thisRef（受拦截实例引用）。
     *
     * <p>在 ENTRY 插桩点压入，EXIT/EXCEPTION 阶段 ctx 注入 userData 后清空。
     * 用于 Handler 在 EXIT 时拿到原始实例（Socket / HttpURLConnection / FileInputStream 等）。</p>
     */
    private static final ThreadLocal<Object> ENTRY_THIS = new ThreadLocal<>();

    /**
     * 全局追踪栈 — 每个线程保存 traceId + spanId 栈帧。
     *
     * <p>栈帧结构：(traceId, spanId)。ENTRY 压栈，EXIT/EXCEPTION 弹栈，
     * 保持 spanId 父子关系。每次调用 Interceptor 时，traceId 始终不变，
     * spanId 是当前栈帧，parentSpanId 是栈帧下方那个 spanId。</p>
     */
    private static final ThreadLocal<Deque<TraceStackFrame>> TRACE_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Handler 注册表：拦截键 → Handler 实例
     */
    private static final Map<String, Interceptor> INTERCEPTOR_MAP = new ConcurrentHashMap<>();

    /**
     * 插桩计数器（线程局部）
     */
    private static final ThreadLocal<Integer> TRANSFORM_COUNT = ThreadLocal.withInitial(() -> 0);

    /**
     * 跨线程追踪上下文存储（key 由用户指定）
     *
     * <p>为防止恶意/异常的 key 海量构造导致 OOM,使用 {@link BoundedLocalCache} 限制:
     * 最多 {@value #MAX_CROSS_THREAD_TRACES} 条,默认 TTL {@value #CROSS_THREAD_TRACE_TTL_MS} ms。
     * 任何 put 操作都强制校验容量上限 + 过期清理。</p>
     */
    private static final BoundedLocalCache<String, TraceEnvelope> CROSS_THREAD_TRACES =
            new BoundedLocalCache<>(MAX_CROSS_THREAD_TRACES, CROSS_THREAD_TRACE_TTL_MS);

    /**
     * MDC 键：traceId（与 SLF4J MDC 桥接）
     */
    private static final String MDC_KEY_TRACE_ID = "traceId";

    /**
     * MDC 键：spanId
     */
    private static final String MDC_KEY_SPAN_ID = "spanId";

    /**
     * MDC 键：className
     */
    private static final String MDC_KEY_CLASS_NAME = "className";

    /**
     * MDC 键：methodName
     */
    private static final String MDC_KEY_METHOD_NAME = "methodName";

    private RuntimeSpy() {
    }

    /**
     * 注册拦截器。
     *
     * <p>Handler 在 start() 中调用此方法注册拦截规则。</p>
     *
     * @param className  目标类名（内部名，如 "org/slf4j/Logger"）
     * @param methodName 目标方法名（如 "info"）
     * @param descriptor 方法描述符（如 "(Ljava/lang/String;)V"）
     * @param point      插桩点（ENTRY / EXIT / LOG_PRE / LOG_POST 等）
     * @param interceptor Handler 实例
     */
    public static void registerInterceptor(String className,
                                           String methodName,
                                           String descriptor,
                                           InterceptPoint point,
                                           Interceptor interceptor) {
        String key = buildKey(className, methodName, point.getKey());
        INTERCEPTOR_MAP.put(key, interceptor);
        // 同步精确插桩规则到 SpyTransformer
        syncTransformerRule(className, methodName, point, true);
        LOG.log(Level.FINE, String.format("注册拦截器: %s -> %s[%s]", key, interceptor.getClass().getSimpleName(), point.getKey()));
    }

    /**
     * 同步精确插桩规则到 SpyTransformer。
     *
     * <p>保证 ASM 转换器在类加载/重变换时对目标方法真正插入字节码。</p>
     *
     * @param className  目标类名（内部名）
     * @param methodName 目标方法名
     * @param point      插桩点
     * @param register   注册为 true，注销为 false
     */
    private static void syncTransformerRule(String className, String methodName,
                                            InterceptPoint point, boolean register) {
        try {
            if (SpyBootstrap.isInitialized()) {
                SpyTransformer transformer = SpyBootstrap.getTransformer();
                if (transformer != null) {
                    if (register) {
                        transformer.registerMethod(className, methodName, point);
                    } else {
                        transformer.unregisterMethod(className, methodName, point);
                    }
                    if (register && SpyBootstrap.getInstrumentation() != null) {
                        retransformLoadedClass(className);
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("同步插桩规则失败: %s.%s.%s", className, methodName, e.getMessage()));
        }
    }

    /**
     * 对已加载的目标类触发重变换 — 关键！否则 handler.start() 在 premain 之后注册时，
     * 类已用空规则集被首次变换过，bytecode 没有真实的 Bootstrap.onIntercept 调用。
     *
     * <p>前提：Agent JAR 已同时追加到 bootstrap classpath（{@code appendToBootstrapClassLoaderSearch}），
     * 因此 Bootstrap 类在 bootstrap 内可解析，对 java/net/Socket 等 JDK 类也可见。</p>
     *
     * @param internalName 类内部名（如 "java/net/Socket"）
     */
    private static void retransformLoadedClass(String internalName) {
        Instrumentation inst = SpyBootstrap.getInstrumentation();
        if (inst == null) {
            return;
        }
        try {
            Class<?> target = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getName().equals(internalName.replace('/', '.'))) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            if (!inst.isModifiableClass(target)) {
                LOG.log(Level.FINE, "跳过不可重变换类: " + internalName);
                return;
            }
            inst.retransformClasses(target);
            LOG.log(Level.INFO, "已对 " + internalName + " 触发重变换");
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "retransform " + internalName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 注销拦截器。
     *
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param point      插桩点
     */
    public static void unregisterInterceptor(String className,
                                             String methodName,
                                             InterceptPoint point) {
        String key = buildKey(className, methodName, point.getKey());
        INTERCEPTOR_MAP.remove(key);
        LOG.log(Level.FINE, String.format("注销拦截器: %s", key));
    }

    /**
     * 批量注销指定 Handler 的所有拦截器。
     *
     * @param interceptor Handler 实例
     */
    public static void unregisterAll(Interceptor interceptor) {
        String handlerName = interceptor.getClass().getSimpleName();
        List<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, Interceptor> entry : INTERCEPTOR_MAP.entrySet()) {
            Interceptor v = entry.getValue();
            if (v != null && v.getClass().getSimpleName().equals(handlerName)) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            INTERCEPTOR_MAP.remove(key);
            syncRemoveRule(key);
        }
        LOG.log(Level.FINE, String.format("注销 %s 的所有拦截器，共 %s 个", handlerName, keysToRemove.size()));
    }

    /**
     * 根据拦截键同步注销 transformer 规则。
     *
     * @param key 拦截键（className#methodName#pointKey）
     */
    private static void syncRemoveRule(String key) {
        String[] parts = key.split(KEY_SEPARATOR, -1);
        if (parts.length != 3) {
            return;
        }
        InterceptPoint point = InterceptPoint.of(parts[2]);
        if (point != null) {
            syncTransformerRule(parts[0], parts[1], point, false);
        }
    }

    /**
     * ASM 字节码插桩入口 — 被 SpyTransformer 通过 INVOKESTATIC 调用。
     *
     * <p>新签名：包含 thisRef，方便 Handler 直接拿到受拦截实例（Socket/HttURLConnection 等）。</p>
     *
     * @param thisRef  受拦截实例（可为 null）
     * @param className  目标类名
     * @param methodName 目标方法名
     * @param descriptor 方法描述符
     * @param pointKey   插桩点标识
     */
    public static void onIntercept(String className,
                                   String methodName,
                                   String descriptor,
                                   String pointKey,
                                   Object thisRef) {
        InterceptPoint point = InterceptPoint.of(pointKey);
        if (point == null) {
            return;
        }

        String key = buildKey(className, methodName, pointKey);
        Interceptor interceptor = INTERCEPTOR_MAP.get(key);

        // 链路追踪栈帧管理
        TraceStackFrame currentFrame = TRACE_STACK.get().peek();
        String traceId = currentFrame != null ? currentFrame.traceId() : null;
        String parentSpanId = currentFrame != null ? currentFrame.spanId() : null;

        if (point == InterceptPoint.ENTRY) {
            // 新建栈帧
            if (traceId == null) {
                traceId = generateId();
            }
            String spanId = generateId();
            // 栈深度保护 — 超过 MAX_TRACE_DEPTH 强制 reset,防止 AOP 死循环或意外递归
            Deque<TraceStackFrame> stack = TRACE_STACK.get();
            if (stack.size() >= MAX_TRACE_DEPTH) {
                LOG.log(Level.WARNING,
                        String.format("追踪栈深度超限(%d),强制 reset 防止 OOM,class=%s method=%s",
                                stack.size(), className, methodName));
                stack.clear();
            }
            stack.push(new TraceStackFrame(traceId, spanId));
            CONTEXT.set(new SpyContext(className, methodName, System.currentTimeMillis()));
            // 保存 thisRef — 供 EXIT 阶段读取
            if (thisRef != null) {
                ENTRY_THIS.set(thisRef);
            }
            // MDC 桥接（SLF4J MDC，业务日志可看到 traceId/spanId）
            putMdc(MDC_KEY_TRACE_ID, traceId);
            putMdc(MDC_KEY_SPAN_ID, spanId);
            putMdc(MDC_KEY_CLASS_NAME, className);
            putMdc(MDC_KEY_METHOD_NAME, methodName);
        }

        // 调用 Interceptor（如果有匹配）
        if (interceptor != null) {
            try {
                TraceStackFrame topFrame = TRACE_STACK.get().peek();
                InterceptContext.TraceStack traceStack = null;
                if (topFrame != null) {
                    traceStack = new InterceptContext.TraceStack(
                            topFrame.traceId(), topFrame.spanId(), parentSpanId);
                }
                // 优先：ctx 显式传入 userData；否则用当前 ENTRY 阶段记录的 thisRef；EXIT 时也能拿到
                Object resolvedUserData = thisRef != null ? thisRef : ENTRY_THIS.get();
                InterceptContext ctx = InterceptContext.builder()
                        .className(className)
                        .methodName(methodName)
                        .descriptor(descriptor)
                        .point(point)
                        .timestamp(System.currentTimeMillis())
                        .userData(resolvedUserData)
                        .build();
                if (traceStack != null) {
                    ctx.setTraceStack(traceStack);
                }
                interceptor.onIntercept(ctx);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, String.format("拦截器执行异常: %s", key, e));
            }
        }

        // 维护 ENTRY/EXIT 计数 + 链路追踪栈弹栈
        if (point == InterceptPoint.EXIT || point == InterceptPoint.EXCEPTION) {
            TraceStackFrame popped = TRACE_STACK.get().poll();
            SpyContext spyCtx = CONTEXT.get();
            CONTEXT.remove();
            if (spyCtx != null) {
                long duration = System.currentTimeMillis() - spyCtx.startTime;
                if (point == InterceptPoint.EXCEPTION) {
                    LOG.log(Level.FINE, String.format("[Spy] %s.%s, duration=%sms [EXCEPTION]", className, methodName, duration));
                } else {
                    LOG.log(Level.FINE, String.format("[Spy] %s.%s, duration=%sms", className, methodName, duration));
                }
            }
            if (popped != null && LOG.isLoggable(java.util.logging.Level.FINE)) {
                LOG.log(Level.FINE, String.format("[Trace] exit span=%s traceId=%s", popped.spanId(), popped.traceId()));
            }
            // 无条件清理 ENTRY_THIS — 即使栈未空,当前方法的 thisRef 也应被释放
            // (防 Socket/File 等大对象引用残留,防止线程池复用时脏数据)
            ENTRY_THIS.remove();
            // 栈空时清 MDC（最外层方法退出）
            if (TRACE_STACK.get().isEmpty()) {
                clearMdc();
            }
        }

        TRANSFORM_COUNT.set(TRANSFORM_COUNT.get() + 1);
    }

    /**
     * 捕获当前线程的追踪栈（用于跨线程传播）。
     *
     * <p>使用示例（在新线程中恢复追踪上下文）：</p>
     * <pre>
     * TraceContextSnapshot snap = RuntimeSpy.capture();
     * new Thread(() -&gt; {
     *     RuntimeSpy.restore(snap);
     *     try {
     *         // 业务代码...
     *     } finally {
     *         RuntimeSpy.clear();
     *     }
     * }).start();
     * </pre>
     *
     * @return 追踪栈快照，调用方负责传递给子线程
     */
    public static TraceContextSnapshot capture() {
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        if (CollectionUtils.isEmpty(stack)) {
            return new TraceContextSnapshot(null, Collections.emptyList());
        }
        TraceStackFrame top = stack.peek();
        return new TraceContextSnapshot(top.traceId(), new ArrayList<>(stack));
    }

    /**
     * 在子线程中恢复追踪上下文（跨线程传播）。
     *
     * <p>调用此方法后，该线程后续的 ENTRY 插桩会作为快照中根 Span 的子 Span，
     * 直到调用 clear() 或 restore(null)。</p>
     *
     * @param snapshot 之前调用 capture() 获得的快照
     */
    public static void restore(TraceContextSnapshot snapshot) {
        if (snapshot == null || CollectionUtils.isEmpty(snapshot.frames())) {
            TRACE_STACK.get().clear();
            return;
        }
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        stack.clear();
        // 只压栈根 traceId + 顶层 spanId，使后续 ENTRY 作为顶层 span 的子节点
        TraceStackFrame top = snapshot.frames().get(snapshot.frames().size() - 1);
        stack.push(new TraceStackFrame(snapshot.traceId(), top.spanId()));
    }

    /**
     * 捕获并以 key 保存追踪上下文（用于通过 ExecutorService 跨线程传递）。
     *
     * @param key 标识键
     * @return 快照
     */
    public static TraceContextSnapshot captureAsKey(String key) {
        TraceContextSnapshot snap = capture();
        if (StringUtils.isNotEmpty(key)) {
            CROSS_THREAD_TRACES.put(key, new TraceEnvelope(snap.frames(), System.currentTimeMillis()));
        }
        return snap;
    }

    /**
     * 用 key 恢复追踪上下文（跨 ExecutorService.submit(Runnable, key) 模式）。
     *
     * @param key 标识键
     */
    public static void restoreByKey(String key) {
        if (StringUtils.isEmpty(key)) {
            return;
        }
        TraceEnvelope env = CROSS_THREAD_TRACES.remove(key);
        if (env == null || CollectionUtils.isEmpty(env.frames)) {
            TRACE_STACK.get().clear();
            return;
        }
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        stack.clear();
        TraceStackFrame top = env.frames.get(env.frames.size() - 1);
        String traceId = top.traceId();
        stack.push(new TraceStackFrame(traceId, top.spanId()));
    }

    /**
     * 生成 16 字符 traceId / spanId（UUID 去横线取前 16 位）。
     *
     * @return ID 字符串
     */
    private static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
    }

    /**
     * 测试辅助 — 生成一个新的 traceId/spanId。
     * 仅用于 e2e 测试断言,生产代码应通过插桩调用。
     */
    public static String generateIdForTest() {
        return generateId();
    }

    /**
     * 测试辅助 — 模拟业务调用 ENTRY 后栈状态。
     * 仅用于 e2e 测试断言隔离性,实际由 SpyTransformer 字节码调用 onIntercept 推入。
     */
    public static void pushTraceForTest(String traceId) {
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        if (stack.size() >= MAX_TRACE_DEPTH) {
            stack.clear();
        }
        stack.push(new TraceStackFrame(traceId, generateId()));
        CONTEXT.set(new SpyContext("test", "method", System.currentTimeMillis()));
    }

    /**
     * 方法入口插桩 — 直接入口。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param descriptor 描述符
     * @param pointKey   插桩点
     * @param thisRef    受拦截实例
     */
    public static void onEntry(String className,
                               String methodName,
                               String descriptor,
                               String pointKey,
                               Object thisRef) {
        onIntercept(className, methodName, descriptor, pointKey, thisRef);
    }

    /**
     * 方法出口插桩 — 直接入口。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param descriptor 描述符
     * @param pointKey   插桩点
     * @param thisRef    受拦截实例
     */
    public static void onExit(String className,
                              String methodName,
                              String descriptor,
                              String pointKey,
                              Object thisRef) {
        onIntercept(className, methodName, descriptor, pointKey, thisRef);
    }

    /**
     * 异常出口插桩 — 由 SpyTransformer 在异常处理器中调用。
     *
     * <p>className 传入的可能是点分隔的全限定名，统一转换为内部名参与路由。</p>
     *
     * @param className  类名（点分隔或内部名均可）
     * @param methodName 方法名
     * @param throwable  异常对象
     */
    public static void onException(String className,
                                   String methodName,
                                   Throwable throwable) {
        String internalName = className.replace('.', '/');
        InterceptPoint point = InterceptPoint.EXCEPTION;
        String key = buildKey(internalName, methodName, point.getKey());
        Interceptor interceptor = INTERCEPTOR_MAP.get(key);
        if (interceptor == null) {
            return;
        }
        try {
            InterceptContext ctx = InterceptContext.builder()
                    .className(internalName)
                    .methodName(methodName)
                    .descriptor("")
                    .point(point)
                    .timestamp(System.currentTimeMillis())
                    .throwable(throwable)
                    .build();
            interceptor.onIntercept(ctx);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, String.format("异常拦截器执行异常: %s", key, e));
        }
    }

    /**
     * 构建拦截键。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param pointKey   插桩点 key
     * @return 拦截键
     */
    private static String buildKey(String className, String methodName, String pointKey) {
        return className + KEY_SEPARATOR + methodName + KEY_SEPARATOR + pointKey;
    }

    /**
     * 获取当前插桩上下文。
     *
     * @return SpyContext
     */
    public static SpyContext currentContext() {
        return CONTEXT.get();
    }

    /**
     * 清除当前插桩上下文。
     */
    public static void clearContext() {
        CONTEXT.remove();
    }

    /**
     * 获取插桩总次数。
     *
     * @return 次数
     */
    public static int getInterceptCount() {
        return TRANSFORM_COUNT.get();
    }

    /**
     * 写入 SLF4J MDC（业务日志可看到 traceId/spanId）。
     *
     * <p>使用反射调用 {@code org.slf4j.MDC.put}，避免 spy 模块强依赖 slf4j-api。
     * SLF4J 不在 classpath 时静默跳过。</p>
     *
     * @param key   MDC key
     * @param value MDC value
     */
    public static void putMdc(String key, String value) {
        if (key == null) {
            return;
        }
        try {
            Class<?> mdcClass = Class.forName("org.slf4j.MDC");
            mdcClass.getMethod("put", String.class, String.class).invoke(null, key, value);
        } catch (ClassNotFoundException e) {
            // slf4j 不在 classpath，静默跳过
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("MDC.put 失败 (%s=%s): %s", key, value, e.getMessage()));
        }
    }

    /**
     * 清除 SLF4J MDC 中所有 RuntimeSpy 写入的键。
     *
     * <p>使用反射调用 {@code org.slf4j.MDC.remove}。</p>
     */
    public static void clearMdc() {
        try {
            Class<?> mdcClass = Class.forName("org.slf4j.MDC");
            mdcClass.getMethod("remove", String.class).invoke(null, MDC_KEY_TRACE_ID);
            mdcClass.getMethod("remove", String.class).invoke(null, MDC_KEY_SPAN_ID);
            mdcClass.getMethod("remove", String.class).invoke(null, MDC_KEY_CLASS_NAME);
            mdcClass.getMethod("remove", String.class).invoke(null, MDC_KEY_METHOD_NAME);
        } catch (ClassNotFoundException e) {
            // 静默跳过
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("MDC.remove 失败: %s", e.getMessage()));
        }
    }

    /**
     * 获取已注册的拦截器数量。
     *
     * @return 数量
     */
    public static int getRegisteredCount() {
        return INTERCEPTOR_MAP.size();
    }

    /**
     * 清除所有拦截器注册与当前线程 ThreadLocal。
     */
    public static void clear() {
        INTERCEPTOR_MAP.clear();
        CONTEXT.remove();
        ENTRY_THIS.remove();
        TRANSFORM_COUNT.remove();
        TRACE_STACK.get().clear();
        clearMdc();
        LOG.log(Level.INFO, "RuntimeSpy 已清除");
    }

    /**
     * 仅清理当前线程 ThreadLocal — 不影响全局拦截器注册。
     * 适用于业务线程进入时兜底清理(防止线程池复用导致跨请求脏数据)。
     */
    public static void clearThreadLocal() {
        CONTEXT.remove();
        ENTRY_THIS.remove();
        TRACE_STACK.get().clear();
        TRANSFORM_COUNT.remove();
        clearMdc();
    }

    /**
     * 获取当前线程追踪栈的 traceId（栈顶）。
     *
     * @return traceId，栈空时返回 null
     */
    public static String getCurrentTraceId() {
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        if (CollectionUtils.isEmpty(stack)) {
            return null;
        }
        return stack.peek().traceId();
    }

    /**
     * 获取当前线程追踪栈的 spanId（栈顶）。
     *
     * @return spanId，栈空时返回 null
     */
    public static String getCurrentSpanId() {
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        if (CollectionUtils.isEmpty(stack)) {
            return null;
        }
        return stack.peek().spanId();
    }

    /**
     * 获取当前线程追踪栈深度。
     *
     * @return 栈深度（0 表示无追踪）
     */
    public static int getTraceStackSize() {
        Deque<TraceStackFrame> stack = TRACE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    /**
     * 包装 Runnable — 在新线程中执行前自动恢复追踪上下文。
     *
     * <p>使用示例：</p>
     * <pre>
     * executor.submit(RuntimeSpy.wrap(() -> doWork()));
     * </pre>
     *
     * @param task 原始任务
     * @return 包装后的任务
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return null;
        }
        TraceContextSnapshot snapshot = capture();
        return () -> {
            restore(snapshot);
            try {
                task.run();
            } finally {
                clearMdc();
            }
        };
    }

    /**
     * 包装 Callable — 返回泛型版本。
     *
     * @param task 原始任务
     * @param <T> 返回类型
     * @return Callable
     */
    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> task) {
        if (task == null) {
            return null;
        }
        TraceContextSnapshot snapshot = capture();
        return () -> {
            restore(snapshot);
            try {
                return task.call();
            } finally {
                clearMdc();
            }
        };
    }

    /**
     * 包装 Thread — 启动时自动恢复追踪上下文。
     *
     * @param thread 原始 Thread
     * @return 包装后的 Thread
     */
    public static Thread wrap(Thread thread) {
        if (thread == null) {
            return null;
        }
        TraceContextSnapshot snapshot = capture();
        java.lang.reflect.Field targetField;
        try {
            targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            Runnable originalTarget = (Runnable) targetField.get(thread);
            targetField.set(thread, (Runnable) () -> {
                restore(snapshot);
                try {
                    if (originalTarget != null) {
                        originalTarget.run();
                    }
                } finally {
                    clearMdc();
                }
            });
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("包装 Thread 失败: %s", e.getMessage()));
        }
        return thread;
    }

    /**
     * 插桩上下文（线程局部）。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param startTime  开始时间
     * @author CH
     * @since 4.0.0.42
     */
    public record SpyContext(
            String className,
            String methodName,
            long startTime
    ) {
    }

    /**
     * 追踪栈帧。
     *
     * @param traceId 全局追踪 ID
     * @param spanId  当前 Span ID
     * @author CH
     * @since 4.0.0.42
     */
    public record TraceStackFrame(
            String traceId,
            String spanId
    ) {
    }

    /**
     * 追踪上下文快照 — 用于跨线程传递。
     *
     * @param traceId 根 traceId
     * @param frames  追踪栈（按从栈底到栈顶顺序）
     * @author CH
     * @since 4.0.0.42
     */
    public record TraceContextSnapshot(
            String traceId,
            List<TraceStackFrame> frames
    ) {
    }

    /**
     * 跨线程追踪上下文条目 — 携带过期时间戳,支持 TTL 清理。
     */
    private static final class TraceEnvelope {
        final List<TraceStackFrame> frames;
        final long createdAt;

        TraceEnvelope(List<TraceStackFrame> frames, long createdAt) {
            this.frames = frames;
            this.createdAt = createdAt;
        }
    }

    /**
     * 有界本地缓存 — 限制最大条目数 + 自动过期清理。
     *
     * <p>替代裸 ConcurrentHashMap,防止恶意/异常 key 海量构造导致 OOM。</p>
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class BoundedLocalCache<K, V> {
        /**
         * 映射
         */
        private final Map<K, V> map = new ConcurrentHashMap<>();
        /**
         * max大小
         */
        private final int maxSize;
        /**
         * ttl Ms
         */
        private final long ttlMs;
        /**
         * last Cleanup
         */
        private final AtomicLong lastCleanup = new AtomicLong();

        BoundedLocalCache(int maxSize, long ttlMs) {
            this.maxSize = maxSize;
            this.ttlMs = ttlMs;
        }

        synchronized V put(K key, V value) {
            // 容量超限：移除最旧的 10%
            if (map.size() >= maxSize) {
                int evict = Math.max(1, map.size() / 10);
                Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
                while (it.hasNext() && evict > 0) {
                    it.next();
                    it.remove();
                    evict--;
                }
            }
            return map.put(key, value);
        }

        synchronized V get(K key) {
            // 每 60s 触发一次过期清理
            long now = System.currentTimeMillis();
            if (now - lastCleanup.get() > 60_000L) {
                cleanup(now);
                lastCleanup.set(now);
            }
            V v = map.get(key);
            if (v instanceof TraceEnvelope) {
                if (now - ((TraceEnvelope) v).createdAt > ttlMs) {
                    map.remove(key);
                    return null;
                }
            }
            return v;
        }

        synchronized V remove(K key) {
            return map.remove(key);
        }

        synchronized void clear() {
            map.clear();
        }

        synchronized int size() {
            return map.size();
        }

        private void cleanup(long now) {
            map.entrySet().removeIf(e -> {
                if (e.getValue() instanceof TraceEnvelope) {
                    return now - ((TraceEnvelope) e.getValue()).createdAt > ttlMs;
                }
                return false;
            });
        }
    }

    /**
     * 拦截器接口 — Handler 实现此接口接收插桩事件。
     *
     * @author CH
     * @since 4.0.0.42
     */
    public interface Interceptor {

        /**
         * 接收插桩事件。
         *
         * @param context 插桩上下文
         */
        void onIntercept(InterceptContext context);
    }
}
