package com.chua.runtime.apm.handler;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志拦截器 — 劫持 SLF4J/Jul/APCL 日志框架和 System.out/err。
 *
 * <p>字节码插桩实现：</p>
 * <p>对目标日志类（如 org/slf4j/Logger）的 info/debug/warn/error 方法，
 * 在方法入口插入 RuntimeSpy.onIntercept()，方法出口也插入调用。</p>
 *
 * <p>ASM 插入的字节码：</p>
 * <pre>
 * 入口：
 *   LDC "org/slf4j/Logger"
 *   LDC "info"
 *   LDC "(Ljava/lang/String;)V"
 *   LDC "log_pre"
 *   INVOKESTATIC RuntimeSpy.onIntercept
 *   // 原始方法体...
 *   LDC "org/slf4j/Logger"
 *   LDC "info"
 *   LDC "(Ljava/lang/String;)V"
 *   LDC "log_post"
 *   INVOKESTATIC RuntimeSpy.onIntercept
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LogHandler implements Plugin, RuntimeSpy.Interceptor {
    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(LogHandler.class.getName());

    /**
     * 插件名称
     */
    private static final String HANDLER_NAME = "log-handler";

    /**
     * 插件版本
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 启用配置属性 key
     */
    private static final String PROP_LOG_ENABLED = "log.enabled";

    /**
     * 默认启用值
     */
    private static final String DEFAULT_ENABLED = "true";

    /**
     * SLF4J Logger 类名（内部名格式）
     */
    private static final String SLF4J_LOGGER = "org/slf4j/Logger";

    /**
     * SLF4J LoggerFactory 类名
     */
    private static final String SLF4J_FACTORY = "org/slf4j/LoggerFactory";

    /**
     * java.util.logging Logger 类名
     */
    private static final String JUL_LOGGER = "java/util/logging/Logger";

    /**
     * Apache Commons Logging Log 类名
     */
    private static final String APCL_LOG = "org/apache/commons/logging/Log";

    /**
     * Log4j2 Logger 类名
     */
    private static final String LOG4J2_LOGGER = "org/apache/logging/log4j/Logger";

    /**
     * 日志方法名
     */
    private static final String[] LOG_METHODS = {"info", "debug", "warn", "error", "trace"};

    /**
     * 日志方法描述符
     */
    private static final String[] LOG_METHOD_DESCS = {
            "(Ljava/lang/String;)V",
            "(Ljava/lang/String;Ljava/lang/Throwable;)V",
            "(Ljava/lang/String;Ljava/lang/Object;)V",
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
            "(Ljava/lang/String;[Ljava/lang/Object;)V"
    };

    /**
     * 收集的日志记录
     */
    private final BoundedRecordList<LogEntry> logEntries;

    /**
     * 最大日志数量
     */
    private static final int MAX_LOGS = 10000;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 是否已劫持 System.out/err
     */
    private final AtomicBoolean streamsHijacked;

    /**
     * 原始 System.out
     */
    private PrintStream originalOut;

    /**
     * 原始 System.err
     */
    private PrintStream originalErr;

    /**
     * 插件上下文
     */
    private PluginContext context;

    /** 创建 LogHandler 实例 */
    public LogHandler() {
        this.logEntries = new com.chua.runtime.apm.handler.BoundedRecordList<>(MAX_LOGS);
        this.streamsHijacked = new AtomicBoolean(false);
    }

    @Override
    /** Name */
    public String name() {
        return HANDLER_NAME;
    }

    @Override
    /** Version */
    public String version() {
        return HANDLER_VERSION;
    }

    @Override
    /** 初始化 */
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = DEFAULT_ENABLED.equals(context.getProperty(PROP_LOG_ENABLED, DEFAULT_ENABLED));
        LOG.log(Level.INFO, String.format("LogHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    /** 开始 */
    public void start() throws Exception {
        if (!enabled) {
            return;
        }

        // 注册 SLF4J 日志拦截
        registerSlf4jIntercepts();

        // 注册 JUL 日志拦截
        registerJulIntercepts();

        // 注册 Apache Commons Logging 拦截
        registerApclIntercepts();

        // 注册 Log4j2 拦截
        registerLog4j2Intercepts();

        // 劫持 System.out/err
        hijackSystemStreams();

        LOG.log(Level.INFO, "LogHandler 启动完成，已注册日志拦截点");
    }

    @Override
    /** 停止 */
    public void stop() throws Exception {
        this.enabled = false;
        // 恢复 System.out/err
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        if (originalErr != null) {
            System.setErr(originalErr);
        }
        // 注销所有拦截器
        RuntimeSpy.unregisterAll(this);
        LOG.log(Level.INFO, "LogHandler 停止");
    }

    @Override
    /** Status */
    public String status() {
        return String.format("LogHandler[enabled=%s, logs=%d]", enabled, logEntries.size());
    }

    @Override
    /** 是否Running */
    public boolean isRunning() {
        return enabled;
    }

    /**
     * 接收插桩事件 — 由 RuntimeSpy 路由调用。
     *
     * @param context 插桩上下文
     */
    @Override
    public void onIntercept(InterceptContext context) {
        if (!enabled) {
            return;
        }

        String className = context.getClassName();
        String methodName = context.getMethodName();
        InterceptPoint point = context.getPoint();

        // 判断是入口还是出口
        if (point == InterceptPoint.LOG_PRE) {
            String level = mapMethodToLevel(methodName);
            if (level == null) {
                return;
            }
            Object loggerInstance = context.getUserData();
            String loggerName = extractLoggerName(loggerInstance, className);
            addLogEntry(LogEntry.builder()
                    .timestamp(context.getTimestamp())
                    .level(level)
                    .logger(loggerName)
                    .message("[Log intercepted]")
                    .className(context.getReadableClassName())
                    .methodName(methodName)
                    .build());
        } else if (point == InterceptPoint.LOG_POST) {
            // 日志方法调用后：补充处理
            String level = mapMethodToLevel(methodName);
            if (level != null) {
                LOG.log(Level.FINE, String.format("[LogPost] %s.%s level=%s", context.getReadableClassName(), methodName, level));
            }
        }
    }

    /**
     * 注册 SLF4J 日志拦截。
     */
    private void registerSlf4jIntercepts() {
        for (String method : LOG_METHODS) {
            for (String desc : LOG_METHOD_DESCS) {
                RuntimeSpy.registerInterceptor(
                        SLF4J_LOGGER, method, desc,
                        InterceptPoint.LOG_PRE, this);
                RuntimeSpy.registerInterceptor(
                        SLF4J_LOGGER, method, desc,
                        InterceptPoint.LOG_POST, this);
            }
        }
        LOG.log(Level.FINE, String.format("已注册 SLF4J 拦截点: %s 方法 × %s 描述符", LOG_METHODS.length, LOG_METHOD_DESCS.length));
    }

    /**
     * 注册 java.util.logging 拦截。
     */
    private void registerJulIntercepts() {
        String[] julMethods = {"log", "fine", "warning", "severe", "config", "info"};
        String[] julDescs = {
                "(Ljava/util/logging/Level;Ljava/lang/String;)V",
                "(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Throwable;)V",
                "(Ljava/util/logging/Level;Ljava/lang/String;Ljava/lang/Object;)V",
                "(Ljava/util/logging/Level;[Ljava/lang/Object;)V"
        };
        for (String method : julMethods) {
            for (String desc : julDescs) {
                RuntimeSpy.registerInterceptor(
                        JUL_LOGGER, method, desc,
                        InterceptPoint.LOG_PRE, this);
            }
        }
        LOG.log(Level.FINE, "已注册 JUL 拦截点");
    }

    /**
     * 注册 Apache Commons Logging 拦截。
     */
    private void registerApclIntercepts() {
        for (String method : LOG_METHODS) {
            for (String desc : LOG_METHOD_DESCS) {
                RuntimeSpy.registerInterceptor(
                        APCL_LOG, method, desc,
                        InterceptPoint.LOG_PRE, this);
            }
        }
        LOG.log(Level.FINE, "已注册 APCL 拦截点");
    }

    /**
     * 注册 Log4j2 拦截。
     */
    private void registerLog4j2Intercepts() {
        for (String method : LOG_METHODS) {
            for (String desc : LOG_METHOD_DESCS) {
                RuntimeSpy.registerInterceptor(
                        LOG4J2_LOGGER, method, desc,
                        InterceptPoint.LOG_PRE, this);
            }
        }
        LOG.log(Level.FINE, "已注册 Log4j2 拦截点");
    }

    /**
     * 劫持 System.out 和 System.err。
     */
    private void hijackSystemStreams() {
        if (!streamsHijacked.compareAndSet(false, true)) {
            return;
        }

        this.originalOut = System.out;
        this.originalErr = System.err;

        System.setOut(new LoggingPrintStream(originalOut, "stdout"));
        System.setErr(new LoggingPrintStream(originalErr, "stderr"));

        LOG.log(Level.FINE, "System.out/err 劫持完成");
    }

    /**
     * 方法名映射到日志级别。
     *
     * @param methodName 方法名
     * @return 日志级别
     */
    private String mapMethodToLevel(String methodName) {
        return switch (methodName.toLowerCase()) {
            case "info" -> "INFO";
            case "debug" -> "DEBUG";
            case "warn" -> "WARN";
            case "error", "severe" -> "ERROR";
            case "trace" -> "TRACE";
            case "fine" -> "DEBUG";
            case "config" -> "INFO";
            default -> null;
        };
    }

    /**
     * 从 Logger 实例反射获取业务类名。
     *
     * @param loggerInstance Logger 实例（SLF4J/JUL/APCL/Log4j2）
     * @param className 类内部名（兜底用）
     * @return 业务类名（如 com.example.demo.DemoController）
     */
    private String extractLoggerName(Object loggerInstance, String className) {
        if (loggerInstance == null) {
            return className.replace('/', '.');
        }
        // 尝试反射调用 getName()（SLF4J Logger、JUL Logger、APCL Log、Log4j2 Logger 均支持）
        try {
            Object result = ReflectUtils.invoke(loggerInstance, "getName", String.class);
            if (result instanceof String && !((String) result).isEmpty()) {
                return (String) result;
            }
        } catch (Exception e) {
            // 反射失败，使用兜底
        }
        return className.replace('/', '.');
    }

    /**
     * 添加日志记录。
     *
     * @param entry 日志条目
     */
    public void addLogEntry(LogEntry entry) {
        logEntries.add(entry);
        // 持久化：日志事件
        try {
            com.chua.runtime.apm.storage.StorageManager.get().appendLog(
                    com.chua.runtime.apm.storage.LogRecord.builder()
                            .timestamp(entry.getTimestamp())
                            .level(entry.getLevel())
                            .logger(entry.getLogger())
                            .className(entry.getClassName())
                            .methodName(entry.getMethodName())
                            .message(entry.getMessage())
                            .traceId(null)
                            .build());
        } catch (Exception e) {
            LOG.log(Level.FINE, "appendLog 异常: " + e.getMessage());
        }
    }

    /**
     * 获取所有日志记录。
     *
     * @return 日志记录列表
     */
    public List<LogEntry> getLogEntries() {
        return logEntries.snapshot();
    }

    /**
     * 获取最近 N 条日志。
     *
     * @param n 条数
     * @return 日志记录列表
     */
    public List<LogEntry> tail(int n) {
        return logEntries.tail(n);
    }

    /**
     * 按关键词搜索日志。
     *
     * @param keyword 关键词
     * @return 匹配的记录
     */
    public List<LogEntry> search(String keyword) {
        List<LogEntry> result = new ArrayList<>();
        String kw = keyword.toLowerCase();
        for (LogEntry entry : logEntries) {
            if (entry.getMessage() != null && entry.getMessage().toLowerCase().contains(kw)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 按级别过滤。
     *
     * @param level 日志级别
     * @return 匹配的记录
     */
    public List<LogEntry> filterByLevel(String level) {
        List<LogEntry> result = new ArrayList<>();
        String lv = level.toUpperCase();
        for (LogEntry entry : logEntries) {
            if (entry.getLevel() != null && entry.getLevel().equals(lv)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 清空日志记录。
     */
    public void clear() {
        logEntries.clear();
    }

    /**
     * 日志输出流包装器。
     */
    private static class LoggingPrintStream extends PrintStream {

        /**
         * 流标识
         */
        private final String streamId;

        LoggingPrintStream(OutputStream out, String streamId) {
            super(out);
            this.streamId = streamId;
        }

        @Override
        /** Println */
        public void println(String x) {
            super.println(x);
        }

        @Override
        /** Println */
        public void println(Object x) {
            super.println(x);
        }
    }
}