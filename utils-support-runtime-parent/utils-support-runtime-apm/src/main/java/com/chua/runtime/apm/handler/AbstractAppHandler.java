package com.chua.runtime.apm.handler;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 应用层传输 Handler 抽象基类 — 抽取 ENTRY/EXIT/EXCEPTION 公共处理逻辑。
 *
 * <p>子类只需声明名称、协议、软件栈、拦截方法列表与目标端点构建逻辑，
 * 公共的记录存储、依赖图同步、生命周期管理全部由基类完成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractAppHandler implements Plugin, RuntimeSpy.Interceptor {
    private static final Logger LOG = Logger.getLogger(AbstractAppHandler.class.getName());

    /**
     * 传输记录列表（有界）
     */
    protected final BoundedRecordList<TransmissionRecord> records;

    /**
     * 是否启用
     */
    protected boolean enabled;

    /**
     * 是否已启动
     */
    protected final AtomicBoolean started;

    protected AbstractAppHandler() {
        this.records = new BoundedRecordList<>(10000);
        this.started = new AtomicBoolean(false);
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.enabled = "true".equals(context.getProperty(enabledKey(), "true"));
        LOG.log(Level.INFO, String.format("%s 初始化完成，启用状态: %s", name(), enabled));
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registerInterceptors();
        LOG.log(Level.INFO, String.format("%s 启动完成，应用层拦截已注册", name()));
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        LOG.log(Level.INFO, String.format("%s 停止", name()));
    }

    @Override
    public String status() {
        return String.format("%s[enabled=%s, records=%d]", name(), enabled, records.size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    @Override
    public void onIntercept(InterceptContext ctx) {
        if (!enabled) {
            return;
        }
        switch (ctx.getPoint()) {
            case ENTRY -> handleEntry(ctx);
            case EXIT -> handleExit(ctx);
            case EXCEPTION -> handleException(ctx);
            default -> {
            }
        }
    }

    /**
     * 当前调用帧（用 ctx.userData 关联 entry/exit）。
     */
    private static final ThreadLocal<TransmissionRecord> CURRENT = new ThreadLocal<>();

    private void handleEntry(InterceptContext ctx) {
        try {
            TransmissionRecord record = new TransmissionRecord();
            record.setTraceId(ctx.getTraceId());
            record.setSpanId(ctx.getSpanId());
            record.setStartTime(System.currentTimeMillis());
            record.setOperation(deriveOperation(ctx));
            record.setSoftware(softwareForEntry(ctx));
            record.setProtocol(protocol());

            Object instance = ctx.getUserData();
            record.setSource(Endpoint.builder()
                    .kind(kindForEntry(ctx))
                    .protocol(protocol())
                    .software(softwareForEntry(ctx))
                    .host(localHost())
                    .port(0)
                    .path("/")
                    .build());
            record.setTarget(buildTarget(ctx, instance));

            CURRENT.set(record);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("%s.entry 异常: %s", name(), e.getMessage()));
        }
    }

    private void handleExit(InterceptContext ctx) {
        try {
            TransmissionRecord record = CURRENT.get();
            if (record == null) {
                return;
            }
            CURRENT.remove();
            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.OK);
            addAndEmit(record, false);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("%s.exit 异常: %s", name(), e.getMessage()));
        }
    }

    private void handleException(InterceptContext ctx) {
        try {
            TransmissionRecord record = CURRENT.get();
            if (record == null) {
                return;
            }
            CURRENT.remove();
            record.setEndTime(System.currentTimeMillis());
            record.setDuration(record.getEndTime() - record.getStartTime());
            record.setStatus(StatusCode.ERROR);
            if (ctx.getThrowable() != null) {
                record.setErrorType(ctx.getThrowable().getClass().getName());
                record.setErrorMessage(ctx.getThrowable().getMessage());
            }
            addAndEmit(record, true);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("%s.exception 异常: %s", name(), e.getMessage()));
        }
    }

    /**
     * 记录 + 同步到存储与依赖图。
     *
     * @param record  传输记录
     * @param isError 是否为错误记录
     */
    private void addAndEmit(TransmissionRecord record, boolean isError) {
        records.add(record);
        try {
            com.chua.runtime.apm.storage.StorageManager.appendTransmission(record);
        } catch (Exception ignore) {
        }
        try {
            DependencyGraphHandler handler = ApmBootstrap.getGlobalHandler(DependencyGraphHandler.class);
            if (handler == null) {
                return;
            }
            if (record.getSource() == null || record.getTarget() == null) {
                return;
            }
            String errorType = isError ? record.getErrorType() : null;
            handler.record(record.getSource(), record.getTarget(), record.getProtocol(),
                    record.getSoftware(), record.getDuration(), isError, errorType);
        } catch (Exception e) {
            LOG.log(Level.FINE, String.format("%s emit 异常: %s", name(), e.getMessage()));
        }
    }

    /**
     * 注册单方法三插桩点（ENTRY/EXIT/EXCEPTION）。
     *
     * @param className  目标类内部名
     * @param methodName 目标方法名
     */
    protected void register(String className, String methodName) {
        RuntimeSpy.registerInterceptor(className, methodName, "", InterceptPoint.ENTRY, this);
        RuntimeSpy.registerInterceptor(className, methodName, "", InterceptPoint.EXIT, this);
        RuntimeSpy.registerInterceptor(className, methodName, "", InterceptPoint.EXCEPTION, this);
    }

    /**
     * 注册类多方法三插桩点。
     *
     * @param className 目标类内部名
     * @param methods   目标方法名数组
     */
    protected void registerAll(String className, String[] methods) {
        for (String method : methods) {
            register(className, method);
        }
    }

    /**
     * 软件栈（默认取 {@link #software()}）。
     *
     * <p>子类可按入口区分子角色（如 Producer/Consumer）覆写此方法。</p>
     *
     * @param ctx 插桩上下文
     * @return 软件栈枚举
     */
    protected Software softwareForEntry(InterceptContext ctx) {
        return software();
    }

    /**
     * 端点角色（默认 CLIENT）。
     *
     * <p>子类可按入口区分子角色（如 Producer/Consumer）覆写此方法。</p>
     *
     * @param ctx 插桩上下文
     * @return 端点角色
     */
    protected EndpointKind kindForEntry(InterceptContext ctx) {
        return EndpointKind.CLIENT;
    }

    /**
     * 注册插桩规则（子类实现）。
     */
    protected abstract void registerInterceptors();

    /**
     * 启用配置项 key。
     *
     * @return 配置 key
     */
    protected abstract String enabledKey();

    /**
     * 软件栈。
     *
     * @return 软件栈枚举
     */
    protected abstract Software software();

    /**
     * 协议。
     *
     * @return 协议枚举
     */
    protected abstract Protocol protocol();

    /**
     * 构建目标端点。
     *
     * @param ctx      插桩上下文
     * @param instance 受拦截实例
     * @return 目标端点
     */
    protected abstract Endpoint buildTarget(InterceptContext ctx, Object instance);

    /**
     * 推导操作描述（方法名转大写）。
     *
     * @param ctx 插桩上下文
     * @return 操作描述
     */
    protected String deriveOperation(InterceptContext ctx) {
        return ctx.getMethodName().toUpperCase();
    }

    /**
     * 获取本机 IP。
     *
     * @return IP 地址，获取失败返回 "localhost"
     */
    protected static String localHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * 从 Statement / Connection 对象反查 JDBC Connection 实例。
     *
     * <p>传入 Connection 时原样返回；传入 Statement 时向上反查 connection 字段。</p>
     *
     * @param jdbcObject JDBC 对象
     * @return Connection 实例，找不到返回 null
     */
    protected Object resolveConnection(Object jdbcObject) {
        if (jdbcObject == null) {
            return null;
        }
        Object conn = findField(jdbcObject, "connection");
        if (conn != null) {
            return conn;
        }
        return jdbcObject;
    }

    /**
     * 在继承链中查找指定字段值。
     *
     * @param owner     对象
     * @param fieldName 字段名
     * @return 字段值，找不到返回 null
     */
    protected static Object findField(Object owner, String fieldName) {
        Class<?> clazz = owner.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从 JDBC URL 解析 host（jdbc:xxx://host:port/db）。
     *
     * @param url JDBC URL
     * @return 主机名，解析失败返回 null
     */
    protected static String parseUrlHost(String url) {
        if (url == null) {
            return null;
        }
        int hostStart = url.indexOf("//");
        if (hostStart < 0) {
            return null;
        }
        String rest = url.substring(hostStart + 2);
        int colon = rest.indexOf(':');
        int slash = rest.indexOf('/');
        int end = colon > 0 ? colon : (slash > 0 ? slash : rest.length());
        if (end <= 0) {
            return null;
        }
        return rest.substring(0, end);
    }

    /**
     * 从 JDBC URL 解析端口。
     *
     * @param url         JDBC URL
     * @param defaultPort 默认端口
     * @return 端口
     */
    protected static int parseUrlPort(String url, int defaultPort) {
        if (url == null) {
            return defaultPort;
        }
        int hostStart = url.indexOf("//");
        if (hostStart < 0) {
            return defaultPort;
        }
        String rest = url.substring(hostStart + 2);
        int colon = rest.indexOf(':');
        int slash = rest.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash)) {
            int end = slash > 0 ? slash : rest.length();
            try {
                return Integer.parseInt(rest.substring(colon + 1, end));
            } catch (NumberFormatException e) {
                return defaultPort;
            }
        }
        return defaultPort;
    }

    /**
     * 从 JDBC URL 解析数据库路径。
     *
     * @param url JDBC URL
     * @return 数据库路径（/dbname），解析失败返回 "/"
     */
    protected static String parseUrlDb(String url) {
        if (url == null) {
            return "/";
        }
        int hostStart = url.indexOf("//");
        if (hostStart < 0) {
            return "/";
        }
        int slash = url.indexOf("/", hostStart + 2);
        if (slash >= 0 && slash < url.length() - 1) {
            int query = url.indexOf('?', slash);
            if (query > 0) {
                return "/" + url.substring(slash + 1, query);
            }
            return "/" + url.substring(slash + 1);
        }
        return "/";
    }

    public List<TransmissionRecord> getRecords() {
        return records.snapshot();
    }
}