package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class LogHandler implements Plugin, RuntimeSpy.Interceptor {

    /**
     * SLF4J Logger 类名
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
    private final List<LogEntry> logEntries;

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

    public LogHandler() {
        this.logEntries = Collections.synchronizedList(new ArrayList<>());
        this.streamsHijacked = new AtomicBoolean(false);
    }

    @Override
    public String name() {
        return "log-handler";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = "true".equals(context.getProperty("log.enabled", "true"));
        log.info("LogHandler 初始化完成，启用状态: {}", enabled);
    }

    @Override
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

        log.info("LogHandler 启动完成，已注册日志拦截点");
    }

    @Override
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
        log.info("LogHandler 停止");
    }

    @Override
    public String status() {
        return String.format("LogHandler[enabled=%s, logs=%d]", enabled, logEntries.size());
    }

    @Override
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
            // 日志方法调用前：提取日志级别
            String level = mapMethodToLevel(methodName);
            if (level == null) {
                return;
            }
            // 这里无法获取日志消息内容（在方法参数中），
            // 实际需要通过 MethodVisitor 获取局部变量
            // 简单实现：记录方法调用
            addLogEntry(LogEntry.builder()
                    .timestamp(context.getTimestamp())
                    .level(level)
                    .logger(resolveLoggerName(className))
                    .message("[Log intercepted]")
                    .className(context.getReadableClassName())
                    .methodName(methodName)
                    .build());
        } else if (point == InterceptPoint.LOG_POST) {
            // 日志方法调用后：补充处理
            String level = mapMethodToLevel(methodName);
            if (level != null) {
                log.trace("[LogPost] {}.{} level={}", context.getReadableClassName(), methodName, level);
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
        log.debug("已注册 SLF4J 拦截点: {} 方法 × {} 描述符", LOG_METHODS.length, LOG_METHOD_DESCS.length);
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
        log.debug("已注册 JUL 拦截点");
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
        log.debug("已注册 APCL 拦截点");
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
        log.debug("已注册 Log4j2 拦截点");
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

        log.debug("System.out/err 劫持完成");
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
     * 解析 Logger 名称。
     *
     * @param className 类名
     * @return Logger 名称
     */
    private String resolveLoggerName(String className) {
        if (SLF4J_LOGGER.equals(className)) {
            return "org.slf4j.Logger";
        } else if (JUL_LOGGER.equals(className)) {
            return "java.util.logging.Logger";
        } else if (APCL_LOG.equals(className)) {
            return "org.apache.commons.logging.Log";
        } else if (LOG4J2_LOGGER.equals(className)) {
            return "org.apache.logging.log4j.Logger";
        }
        return className.replace('/', '.');
    }

    /**
     * 添加日志记录。
     *
     * @param entry 日志条目
     */
    public void addLogEntry(LogEntry entry) {
        if (logEntries.size() >= MAX_LOGS) {
            logEntries.remove(0);
        }
        logEntries.add(entry);
    }

    /**
     * 获取所有日志记录。
     *
     * @return 日志记录列表
     */
    public List<LogEntry> getLogEntries() {
        return Collections.unmodifiableList(logEntries);
    }

    /**
     * 获取最近 N 条日志。
     *
     * @param n 条数
     * @return 日志记录列表
     */
    public List<LogEntry> tail(int n) {
        int size = logEntries.size();
        if (n >= size) {
            return getLogEntries();
        }
        return new ArrayList<>(logEntries.subList(size - n, size));
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
     * 日志条目。
     *
     * @author CH
     * @since 4.0.0.42
     */
     @Data
     @Builder
     public static class LogEntry {

        /**
         * 时间戳
         */
        private long timestamp;

        /**
         * 日志级别
         */
        private String level;

        /**
         * Logger 名称
         */
        private String logger;

        /**
         * 日志消息
         */
        private String message;

        /**
         * 类名
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;

        /**
         * 异常
         */
        private Throwable throwable;
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
        public void println(String x) {
            super.println(x);
        }

        @Override
        public void println(Object x) {
            super.println(x);
        }
    }
}