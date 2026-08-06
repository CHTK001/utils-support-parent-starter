package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 链路追踪拦截器 — 劫持分布式链路追踪。
 *
 * <p>字节码插桩实现：</p>
 * <p>对目标应用类的 public 方法进行入口/出口/异常插桩，生成 Span 树。</p>
 *
 * <p>ASM 插入的字节码：</p>
 * <pre>
 * com.example.OrderService.create():
 *   LDC "com/example/OrderService"    // className
 *   LDC "create"                      // methodName
 *   LDC "()V"                         // descriptor
 *   LDC "entry"                       // pointKey
 *   INVOKESTATIC RuntimeSpy.onIntercept
 *   // 原始方法体...
 *   LDC "com/example/OrderService"    // className
 *   LDC "create"                      // methodName
 *   LDC "()V"                         // descriptor
 *   LDC "exit"                        // pointKey
 *   INVOKESTATIC RuntimeSpy.onIntercept
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TraceHandler implements Plugin, RuntimeSpy.Interceptor {

    /**
     * 追踪上下文
     */
    private final TraceContext traceContext;

    /**
     * 所有 Span 列表
     */
    private final List<Span> spans;

    /**
     * Span ID 到 Span 映射
     */
    private final Map<String, Span> spanMap;

    /**
     * 最大 Span 数
     */
    private static final int MAX_SPANS = 10000;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 插件上下文
     */
    private PluginContext context;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    public TraceHandler() {
        this.traceContext = new TraceContext();
        this.spans = Collections.synchronizedList(new ArrayList<>());
        this.spanMap = new ConcurrentHashMap<>();
        this.started = new AtomicBoolean(false);
    }

    @Override
    public String name() {
        return "trace-handler";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = "true".equals(context.getProperty("trace.enabled", "true"));
        log.info("TraceHandler 初始化完成，启用状态: {}", enabled);
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        // 通过 SpyTransformer 的 ENTRY/EXIT 对所有应用类方法插桩，
        // 实际插桩范围由 include/exclude 过滤控制
        String[] classes = context.getProperty("trace.classes", "").split(",");
        for (String clazz : classes) {
            String name = clazz.trim().replace('.', '/');
            if (name.isEmpty()) {
                continue;
            }
            RuntimeSpy.registerInterceptor(name, "*",
                    "", InterceptPoint.ENTRY, this);
            RuntimeSpy.registerInterceptor(name, "*",
                    "", InterceptPoint.EXIT, this);
            RuntimeSpy.registerInterceptor(name, "*",
                    "", InterceptPoint.EXCEPTION, this);
        }
        log.info("TraceHandler 启动完成，注册追踪类: {}", context.getProperty("trace.classes", ""));
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        log.info("TraceHandler 停止");
    }

    @Override
    public String status() {
        return String.format("TraceHandler[enabled=%s, spans=%d]", enabled, spans.size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 接收插桩事件。
     *
     * @param ctx 插桩上下文
     */
    @Override
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        InterceptPoint point = ctx.getPoint();
        switch (point) {
            case ENTRY -> {
                // 使用 Context 中的 traceId/parentSpanId（由 RuntimeSpy 注入），保持全链路共享
                String traceId = ctx.getTraceId();
                String parentSpanId = ctx.getParentSpanId();
                begin(ctx.getClassName(), ctx.getMethodName(), ctx.getDescriptor(), traceId, parentSpanId);
            }
            case EXIT -> end(ctx.getClassName(), ctx.getMethodName());
            case EXCEPTION -> onError(ctx.getClassName(), ctx.getMethodName(), ctx.getThrowable());
            default -> {
            }
        }
    }

    /**
     * 方法入口插桩（带 traceId/parentSpanId）。
     *
     * @param className    类名
     * @param methodName   方法名
     * @param descriptor   方法描述符
     * @param traceId      全局追踪 ID（可为 null 表示由本方法生成）
     * @param parentSpanId 父 Span ID（嵌套调用时传入）
     * @return Span ID
     */
    public String begin(String className, String methodName, String descriptor,
                        String traceId, String parentSpanId) {
        Span span = new Span();
        span.setTraceId(traceId != null ? traceId : generateTraceId());
        span.setSpanId(generateSpanId());
        span.setParentSpanId(parentSpanId);
        span.setClassName(className);
        span.setMethodName(methodName);
        span.setDescriptor(descriptor);
        span.setStartTime(System.currentTimeMillis());

        traceContext.setSpan(span);
        spans.add(span);
        spanMap.put(span.getSpanId(), span);

        if (spans.size() > MAX_SPANS) {
            Span old = spans.remove(0);
            spanMap.remove(old.getSpanId());
        }

        log.trace("[Trace] BEGIN: traceId={}, parent={}, span={}, class={}.{}",
                span.getTraceId(), parentSpanId != null ? parentSpanId : "root",
                span.getSpanId(), className, methodName);
        return span.getSpanId();
    }

    /**
     * 方法入口插桩（兼容旧调用）。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param descriptor 方法描述符
     * @return Span ID
     */
    public String begin(String className, String methodName, String descriptor) {
        Span parent = traceContext.currentSpan();
        return begin(className, methodName, descriptor,
                parent != null ? parent.getTraceId() : null,
                parent != null ? parent.getSpanId() : null);
    }

    /**
     * 方法出口插桩（正常返回）。
     *
     * @param className  类名
     * @param methodName 方法名
     */
    public void end(String className, String methodName) {
        Span span = traceContext.setSpan(null);
        if (span != null) {
            span.setEndTime(System.currentTimeMillis());
            span.setDuration(span.getEndTime() - span.getStartTime());
            span.setStatus("OK");
            log.trace("[Trace] END: {}.{}, span={}, duration={}ms",
                    className, methodName, span.getSpanId(), span.getDuration());
        }
    }

    /**
     * 异常出口插桩。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param throwable  异常
     */
    public void onError(String className, String methodName, Throwable throwable) {
        Span span = traceContext.setSpan(null);
        if (span != null) {
            span.setEndTime(System.currentTimeMillis());
            span.setDuration(span.getEndTime() - span.getStartTime());
            span.setStatus("ERROR");
            span.setException(throwable != null ? throwable.getMessage() : "unknown");
            log.trace("[Trace] ERROR: {}.{}, span={}, error={}",
                    className, methodName, span.getSpanId(),
                    throwable != null ? throwable.getMessage() : "unknown");
        }
    }

    /**
     * 生成 Span ID。
     *
     * @return Span ID
     */
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 生成 traceId。
     *
     * @return traceId
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 获取所有 Span。
     *
     * @return Span 列表
     */
    public List<Span> getSpans() {
        return Collections.unmodifiableList(spans);
    }

    /**
     * 按 Span ID 获取 Span。
     *
     * @param spanId Span ID
     * @return Span 实例
     */
    public Span getSpan(String spanId) {
        return spanMap.get(spanId);
    }

    /**
     * 获取当前追踪上下文。
     *
     * @return TraceContext 实例
     */
    public TraceContext getTraceContext() {
        return traceContext;
    }

    /**
     * 清空所有 Span。
     */
    public void clear() {
        spans.clear();
        spanMap.clear();
        traceContext.clear();
    }

    /**
     * 追踪上下文 — 线程局部变量。
     */
    public static class TraceContext {

        /**
         * 当前 Span
         */
        private final ThreadLocal<Span> currentSpan = new ThreadLocal<>();

        /**
         * 获取当前 Span。
         *
         * @return Span 实例
         */
        public Span currentSpan() {
            return currentSpan.get();
        }

        /**
         * 设置当前 Span。
         *
         * @param span 新 Span
         * @return 旧 Span
         */
        public Span setSpan(Span span) {
            Span old = currentSpan.get();
            currentSpan.set(span);
            return old;
        }

        /**
         * 清除当前 Span。
         */
        public void clear() {
            currentSpan.remove();
        }
    }

    /**
     * 追踪 Span。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Data
    public static class Span {

        /**
         * 全局追踪 ID（同一根调用链共享）
         */
        private String traceId;

        /**
         * Span ID
         */
        private String spanId;

        /**
         * 父 Span ID
         */
        private String parentSpanId;

        /**
         * 类名
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;

        /**
         * 方法描述符
         */
        private String descriptor;

        /**
         * 开始时间
         */
        private long startTime;

        /**
         * 结束时间
         */
        private long endTime;

        /**
         * 耗时（毫秒）
         */
        private long duration;

        /**
         * 状态
         */
        private String status;

        /**
         * 异常信息
         */
        private String exception;
    }
}
