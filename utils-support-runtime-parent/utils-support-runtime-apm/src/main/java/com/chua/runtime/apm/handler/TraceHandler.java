package com.chua.runtime.apm.handler;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>HTTP 请求追踪（XRebel 风格）：</p>
 * <p>拦截 Tomcat CoyoteAdapter.service()，在请求入口捕获 HTTP 方法、路径、
 * 参数、头部、客户端 IP、响应状态码、响应耗时等信息，以 Span 形式纳入链路追踪。</p>
 *
 * <p>Tomcat 拦截流程：</p>
 * <pre>
 * org.apache.catalina.core.StandardEngineValve.invoke():
 *   LDC "org/apache/catalina/core/StandardEngineValve"
 *   LDC "invoke"
 *   LDC "()"
 *   LDC "entry"
 *   INVOKESTATIC RuntimeSpy.onIntercept
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TraceHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(TraceHandler.class.getName());

    /**
     * 插件名称
     */
    private static final String HANDLER_NAME = "trace-handler";

    /**
     * 插件版本
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 启用配置属性 键
     */
    private static final String PROP_TRACE_ENABLED = "trace.enabled";

    /**
     * 默认启用值
     */
    private static final String DEFAULT_TRACE_ENABLED = "true";

    /**
     * 追踪类列表属性 键（逗号分隔）
     */
    private static final String PROP_TRACE_CLASSES = "trace.classes";

    /**
     * 追踪 标识 长度（UUID 去横线后取前 N 位）
     */
    private static final int ID_LENGTH = 16;

    /**
     * 状态值：成功
     */
    private static final String STATUS_OK = "OK";

    /**
     * 状态值：异常
     */
    private static final String STATUS_ERROR = "ERROR";

    /**
     * 异常占位文本（抛出 为 空 时）
     */
    private static final String UNKNOWN_ERROR = "unknown";

    /**
     * 追踪上下文
     */
    private final TraceContext traceContext;

    /**
     * 所有 Span 列表
     */
    private final List<Span> spans;

    /**
     * Span 标识 到 Span 映射
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
     * 是否启用 HTTP 请求追踪
     */
    private boolean httpTracingEnabled;

    /**
     * 插件上下文
     */
    private PluginContext context;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /**
     * HTTP 请求上下文 — 用于在 Tomcat Valve 拦截中提取请求/响应信息。
     */
    private static final ThreadLocal<HttpRequestContext> HTTP_REQUEST_CONTEXT =
            new ThreadLocal<>();

    /**
     * Tomcat 标准enginevalve 内部名
     */
    private static final String STANDARD_ENGINE_VALVE = "org/apache/catalina/core/StandardEngineValve";

    /**
     * Tomcat coyote适配器 内部名
     */
    private static final String COYOTE_ADAPTER = "org/apache/catalina/core/CoyoteAdapter";

    /**
     * 匹配 Servlet 路径正则
     */
    private static final Pattern URI_PATTERN = Pattern.compile("^(/.*?)(\\?.*)?$");

    /**
     * 提取 Servlet 路径的辅助方法。
     *
     * @param requestObj httpservlet请求 实例
     * @return 请求路径 + 方法，获取失败返回 "?"
     */
    private String extractServletPath(Object requestObj) {
        try {
            String path = (String) ReflectUtils.invoke(requestObj, "getRequestURI", String.class);
            String method = (String) ReflectUtils.invoke(requestObj, "getMethod", String.class);
            return method + " " + (path != null ? path : "/");
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 提取 HTTP 状态码。
     *
     * @param responseObj httpservlet响应 实例
     * @return 状态码，获取失败返回 0
     */
    private int extractStatus(Object responseObj) {
        try {
            return (int) ReflectUtils.invoke(responseObj, "getStatus", int.class);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 提取 HTTP 参数数量。
     *
     * @param requestObj httpservlet请求 实例
     * @return 参数数量，获取失败返回 0
     */
    private int extractParamCount(Object requestObj) {
        try {
            Object map = ReflectUtils.invoke(requestObj, "getParameterMap", Object.class);
            return (int) ReflectUtils.invoke(map, "size", int.class);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 提取 客户端 IP。
     *
     * @param requestObj httpservlet请求 实例
     * @return 客户端 IP，获取失败返回 "?"
     */
    private String extractClientIp(Object requestObj) {
        try {
            String xff = (String) ReflectUtils.invoke(requestObj, "getHeader", String.class, "X-Forwarded-For");
            if (StringUtils.isNotEmpty(xff)) {
                return xff.split(",")[0].trim();
            }
            return (String) ReflectUtils.invoke(requestObj, "getRemoteAddr", String.class);
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * 提取 Accept 头部。
     *
     * @param requestObj httpservlet请求 实例
     * @return Accept 值，获取失败返回 "?"
     */
    private String extractAccept(Object requestObj) {
        try {
            return (String) ReflectUtils.invoke(requestObj, "getHeader", String.class, "Accept");
        } catch (Exception e) {
            return "?";
        }
    }

    /** 创建 追踪处理器 实例 */
    public TraceHandler() {
        this.traceContext = new TraceContext();
        this.spans = Collections.synchronizedList(new ArrayList<>());
        this.spanMap = new ConcurrentHashMap<>();
        this.started = new AtomicBoolean(false);
    }

    @Override
    /** 名称 */
    public String name() {
        return HANDLER_NAME;
    }

    @Override
    /** 版本 */
    public String version() {
        return HANDLER_VERSION;
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = DEFAULT_TRACE_ENABLED.equals(context.getProperty(PROP_TRACE_ENABLED, DEFAULT_TRACE_ENABLED));
        this.httpTracingEnabled = "true".equals(context.getProperty("trace.http.enabled", "true"));
        LOG.log(Level.INFO, String.format("TraceHandler 初始化完成，启用状态: trace=%s http=%s", enabled, httpTracingEnabled));
    }

    @Override
    /** 开始 */
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        // 注册用户指定的追踪类
        String[] classes = context.getProperty(PROP_TRACE_CLASSES, "").split(",");
        for (String clazz : classes) {
            String name = clazz.trim().replace('.', '/');
            if (name.isEmpty()) {
                continue;
            }
            registerClassInterceptors(name);
        }

 // 注册 Tomcat HTTP 请求追踪（xrebel 风格）
        if (httpTracingEnabled) {
            registerTomcatHttpInterceptors();
        }

        LOG.log(Level.INFO, String.format("TraceHandler 启动完成，注册追踪类: %s", context.getProperty(PROP_TRACE_CLASSES, "")));
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, "TraceHandler 停止");
    }

    @Override
    /** 状态 */
    public String status() {
        return String.format("TraceHandler[enabled=%s, spans=%d, http=%s]", enabled, spans.size(), httpTracingEnabled);
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 注册指定类的 ENTRY/EXIT/异常 拦截器。
     *
     * @param className 类内部名
     */
    private void registerClassInterceptors(String className) {
        RuntimeSpy.registerInterceptor(className, "*", "", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(className, "*", "", InterceptPoint.EXIT, this);
    }

    /**
     * 注册 Tomcat HTTP 请求拦截器 — 类似 xrebel 的请求详情展示。
     *
     * <p>拦截点：</p>
     * <ul>
     *   <li>StandardEngineValve.invoke — Servlet 入口，捕获请求路径/参数/Header</li>
     *   <li>CoyoteAdapter.service   — 请求结束，捕获状态码/耗时</li>
     * </ul>
     */
    private void registerTomcatHttpInterceptors() {
        RuntimeSpy.registerInterceptor(STANDARD_ENGINE_VALVE, "invoke",
                "()V", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(COYOTE_ADAPTER, "service",
                "()I", InterceptPoint.EXIT, this);
        LOG.log(Level.FINE, "已注册 Tomcat HTTP 请求追踪拦截点");
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
        String className = ctx.getClassName();

 // HTTP 请求追踪入口：从 标准enginevalve 获取请求信息
        if (STANDARD_ENGINE_VALVE.equals(className) && ctx.getPoint() == InterceptPoint.ENTRY) {
            handleHttpEntry(ctx);
            return;
        }

 // HTTP 请求追踪出口：从 coyote适配器 获取响应状态码
        if (COYOTE_ADAPTER.equals(className) && ctx.getPoint() == InterceptPoint.EXIT) {
            handleHttpExit(ctx);
            return;
        }

        // 普通链路追踪
        InterceptPoint point = ctx.getPoint();
        switch (point) {
            case ENTRY -> {
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
     * HTTP 请求入口拦截 — 类似 xrebel 显示请求路径/方法/参数/头部。
     *
     * <p>从 StandardEngineValve 的 request 对象反射提取：
     * HTTP 方法、URI、参数、Accept 头部、客户端 IP、客户端 IP。</p>
     *
     * @param ctx 插桩上下文
     */
    private void handleHttpEntry(InterceptContext ctx) {
        try {
 // 从 请求 对象提取 Servlet 路径
            Object requestObj = ctx.getUserData();
            if (requestObj == null) {
                return;
            }

            String path = extractServletPath(requestObj);
            String clientIp = extractClientIp(requestObj);
            String accept = extractAccept(requestObj);

            HttpRequestContext httpCtx = new HttpRequestContext();
            httpCtx.setRequestPath(path);
            httpCtx.setClientIp(clientIp);
            httpCtx.setAccept(accept);
            httpCtx.setStartTime(System.currentTimeMillis());

 // 以 Span 形式记录 HTTP 请求（xrebel 风格）
            String traceId = ctx.getTraceId();
            String parentSpanId = ctx.getParentSpanId();
            String spanId = begin(
                    classNameToReadable(STANDARD_ENGINE_VALVE),
                    "HTTP_REQUEST",
                    "(Ljavax/servlet/Servlet;Ljavax/servlet/ServletRequest;)V",
                    traceId, parentSpanId
            );
            httpCtx.setSpanId(spanId);
            httpCtx.setTraceId(traceId != null ? traceId : generateTraceId());
            HTTP_REQUEST_CONTEXT.set(httpCtx);

            LOG.log(Level.FINE, String.format("[Trace-HTTP] %s clientIp=%s accept=%s", path, clientIp, accept));
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("TraceHandler.handleHttpEntry 处理异常: %s", e.getMessage()));
        }
    }

    /**
     * HTTP 请求出口拦截 — 类似 xrebel 显示响应状态码和耗时。
     *
     * <p>从 CoyoteAdapter 或 ThreadLocal 中的 HttpRequestContext 补充响应状态码和耗时。</p>
     *
     * @param ctx 插桩上下文
     */
    private void handleHttpExit(InterceptContext ctx) {
        try {
            HttpRequestContext httpCtx = HTTP_REQUEST_CONTEXT.get();
            if (httpCtx == null) {
                return;
            }
            HTTP_REQUEST_CONTEXT.remove();

 // 尝试从 响应 对象提取状态码
            Object responseObj = ctx.getUserData();
            int status = 0;
            if (responseObj != null) {
                status = extractStatus(responseObj);
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - httpCtx.getStartTime();

            // 结束 Span
            Span span = traceContext.currentSpan();
            if (span != null) {
                span.setEndTime(endTime);
                span.setDuration(duration);
                span.setStatus(status >= 400 ? STATUS_ERROR : STATUS_OK);
                span.setStatusCode(status);
                span.setErrorMessage(status > 0 ? "HTTP " + status : null);
                span.setRequestPath(httpCtx.getRequestPath());
                span.setClientIp(httpCtx.getClientIp());
                span.setAccept(httpCtx.getAccept());

                LOG.log(Level.FINE, String.format("[Trace-HTTP] %s status=%s duration=%sms clientIp=%s", httpCtx.getRequestPath(), status, duration, httpCtx.getClientIp()));
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("TraceHandler.handleHttpExit 处理异常: %s", e.getMessage()));
        }
    }

    /**
     * 方法入口插桩（带 追踪id/父spanid）。
     *
     * @param className    类名
     * @param methodName   方法名
     * @param descriptor   方法描述符
     * @param traceId      全局追踪 标识（可为 空 表示由本方法生成）
     * @param parentSpanId 父 Span 标识（嵌套调用时传入）
     * @return Span 标识
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

        LOG.log(Level.FINE, String.format("[Trace] BEGIN: traceId=%s, parent=%s, span=%s, class=%s.%s", span.getTraceId(), parentSpanId != null ? parentSpanId : "root", span.getSpanId(), className, methodName));
        return span.getSpanId();
    }

    /**
     * 方法入口插桩（兼容旧调用）。
     *
     * @param className  类名
     * @param methodName 方法名
     * @param descriptor 方法描述符
     * @return Span 标识
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
            span.setStatus(STATUS_OK);
            LOG.log(Level.FINE, String.format("[Trace] END: %s.%s, span=%s, duration=%sms", className, methodName, span.getSpanId(), span.getDuration()));
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
            span.setStatus(STATUS_ERROR);
            String errorMsg = throwable != null ? throwable.getMessage() : UNKNOWN_ERROR;
            span.setException(errorMsg);
            LOG.log(Level.FINE, String.format("[Trace] ERROR: %s.%s, span=%s, error=%s", className, methodName, span.getSpanId(), errorMsg));
        }
    }

    /**
     * 生成 追踪id。
     *
     * @return traceId（16 字符）
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
    }

    /**
     * 生成 Span 标识。
     *
     * @return Span 标识（16 字符）
     */
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
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
     * 按 Span 标识 获取 Span。
     *
     * @param spanId Span 标识
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
     * 将内部类名转换为点分隔的 Java 类名。
     *
     * @param internalName 内部名（如 org/Apache/catalina/核心/标准enginevalve）
     * @return 点分隔类名
     */
    private String classNameToReadable(String internalName) {
        return internalName != null ? internalName.replace('/', '.') : "";
    }

    /**
     * HTTP 请求上下文 — 线程局部存储，用于关联请求入口和出口的信息。
     *
     * @since 4.0.0.42
     * @author CH
     */
    private static class HttpRequestContext {

        /**
         * HTTP 请求路径（含方法名，如 获取 /api/订单）
         */
        private String requestPath;

        /**
         * 客户端 IP
         */
        private String clientIp;

        /**
         * Accept 头部
         */
        private String accept;

        /**
         * 请求开始时间（毫秒）
         */
        private long startTime;

        /**
         * 关联的 Span 标识
         */
        private String spanId;

        /**
         * 关联的 追踪 标识
         */
        private String traceId;

        /**
         * 获取请求路径
         *
         * @return 获取请求路径的结果
         */
        public String getRequestPath() {
            return requestPath;
        }

        /**
         * 设置请求路径
         *
         * @param requestPath 请求路径
         */
        public void setRequestPath(String requestPath) {
            this.requestPath = requestPath;
        }

        /**
         * 获取客户端ip
         *
         * @return 获取客户端ip的结果
         */
        public String getClientIp() {
            return clientIp;
        }

        /**
         * 设置客户端ip
         *
         * @param clientIp 客户端ip
         */
        public void setClientIp(String clientIp) {
            this.clientIp = clientIp;
        }

        /**
         * 获取Accept
         *
         * @return 获取accept的结果
         */
        public String getAccept() {
            return accept;
        }

        /**
         * 设置Accept
         *
         * @param accept accept
         */
        public void setAccept(String accept) {
            this.accept = accept;
        }

        /**
         * 获取开始时间
         *
         * @return 获取启动时间的结果
         */
        public long getStartTime() {
            return startTime;
        }

        /**
         * 设置开始时间
         *
         * @param startTime 启动时间
         */
        public void setStartTime(long startTime) {
            this.startTime = startTime;
        }

        /**
         * 获取spanid
         *
         * @return 获取spanid的结果
         */
        public String getSpanId() {
            return spanId;
        }

        /**
         * 设置spanid
         *
         * @param spanId spanid
         */
        public void setSpanId(String spanId) {
            this.spanId = spanId;
        }

        /**
         * 获取追踪id
         *
         * @return 获取追踪id的结果
         */
        public String getTraceId() {
            return traceId;
        }

        /**
         * 设置追踪id
         *
         * @param traceId 追踪标识
         */
        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }
    }

    /**
     * 追踪上下文 — 线程局部变量。
     *
     * @since 4.0.0.42
     * @author CH
     */
    public static class TraceContext {

        /**
         * 当前 Span
         */
        private final ThreadLocal<Span> currentSpan = new ThreadLocal<>();

        /**
         * 当前span
         *
         * @return 当前span的结果
         */
        public Span currentSpan() {
            return currentSpan.get();
        }

        /**
         * 设置Span
         *
         * @param span span
         * @return 设置span的结果
         */
        public Span setSpan(Span span) {
            Span old = currentSpan.get();
            currentSpan.set(span);
            return old;
        }

        /** Clear */
        public void clear() {
            currentSpan.remove();
        }
    }

    /**
     * 追踪 Span。
     *
     * @since 4.0.0.42
     * @author CH
     */
    @Data
    public static class Span {

        /**
         * 全局追踪 标识（同一根调用链共享）
         */
        private String traceId;

        /**
         * Span 标识
         */
        private String spanId;

        /**
         * 父 Span 标识（根调用为 空）
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
         * 开始时间（毫秒）
         */
        private long startTime;

        /**
         * 结束时间（毫秒）
         */
        private long endTime;

        /**
         * 耗时（毫秒）
         */
        private long duration;

        /**
         * 状态（OK / 错误）
         */
        private String status;

        /**
         * 异常信息
         */
        private String exception;

        /**
         * HTTP 状态码
         */
        private int statusCode;

        /**
         * 错误信息
         */
        private String errorMessage;

        /**
         * HTTP 请求路径
         */
        private String requestPath;

        /**
         * 客户端 IP
         */
        private String clientIp;

        /**
         * Accept 头部
         */
        private String accept;
    }
}
