package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.spy.RuntimeSpy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件拦截器 — 劫持所有文件 I/O 操作。
 *
 * <p>字节码插桩实现：</p>
 * <p>对目标文件类（如 java/io/FileInputStream）的 read/write 方法，
 * 在方法入口/出口插入 RuntimeSpy.onIntercept()。</p>
 *
 * <p>ASM 插入的字节码：</p>
 * <pre>
 * FileInputStream.read([BII):
 *   LDC "java/io/FileInputStream"     // className
 *   LDC "read"                         // methodName
 *   LDC "([BII)I"                      // descriptor
 *   LDC "file_open_pre"                // pointKey
 *   INVOKESTATIC RuntimeSpy.onIntercept
 *   // 原始方法体...
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FileHandler implements Plugin, RuntimeSpy.Interceptor {

    /**
     * FileInputStream 类名
     */
    private static final String FILE_INPUT = "java/io/FileInputStream";

    /**
     * FileOutputStream 类名
     */
    private static final String FILE_OUTPUT = "java/io/FileOutputStream";

    /**
     * RandomAccessFile 类名
     */
    private static final String RANDOM_FILE = "java/io/RandomAccessFile";

    /**
     * 文件操作记录
     */
    private final List<FileRecord> records;

    /**
     * 最大记录数
     */
    private static final int MAX_RECORDS = 10000;

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

    public FileHandler() {
        this.records = Collections.synchronizedList(new ArrayList<>());
        this.started = new AtomicBoolean(false);
    }

    @Override
    public String name() {
        return "file-handler";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.enabled = "true".equals(context.getProperty("file.enabled", "true"));
        log.info("FileHandler 初始化完成，启用状态: {}", enabled);
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registerFileIntercepts();
        log.info("FileHandler 启动完成，已注册文件拦截点");
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            RuntimeSpy.unregisterAll(this);
        }
        log.info("FileHandler 停止");
    }

    @Override
    public String status() {
        return String.format("FileHandler[enabled=%s, records=%d]", enabled, records.size());
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
            case FILE_OPEN_PRE -> recordOperation(ctx, "open");
            case FILE_OPEN_POST -> recordOperation(ctx, "opened");
            case FILE_READ_PRE -> recordOperation(ctx, "read");
            default -> {
            }
        }
    }

    /**
     * 记录文件操作。
     *
     * @param ctx       插桩上下文
     * @param operation 操作类型
     */
    private void recordOperation(InterceptContext ctx, String operation) {
        FileRecord record = new FileRecord();
        record.setTimestamp(ctx.getTimestamp());
        record.setOperation(operation);
        record.setPath("");
        record.setBytesRead(0);
        record.setBytesWritten(0);
        record.setDuration(0);
        record.setStatus("ok");
        record.setClassName(ctx.getReadableClassName());
        record.setMethodName(ctx.getMethodName());
        addRecord(record);
    }

    /**
     * 注册文件 I/O 拦截点。
     */
    private void registerFileIntercepts() {
        registerInputStreamIntercepts();
        registerOutputStreamIntercepts();
        registerRandomAccessIntercepts();
        log.debug("已注册文件 I/O 拦截点");
    }

    /**
     * 注册 FileInputStream 拦截。
     */
    private void registerInputStreamIntercepts() {
        RuntimeSpy.registerInterceptor(FILE_INPUT, "<init>",
                "(Ljava/lang/String;)V", InterceptPoint.FILE_OPEN_PRE, this);
        RuntimeSpy.registerInterceptor(FILE_INPUT, "<init>",
                "(Ljava/io/File;)V", InterceptPoint.FILE_OPEN_PRE, this);
        RuntimeSpy.registerInterceptor(FILE_INPUT, "read",
                "([BII)I", InterceptPoint.FILE_READ_PRE, this);
        log.debug("已注册 FileInputStream 拦截点");
    }

    /**
     * 注册 FileOutputStream 拦截。
     */
    private void registerOutputStreamIntercepts() {
        RuntimeSpy.registerInterceptor(FILE_OUTPUT, "<init>",
                "(Ljava/lang/String;)V", InterceptPoint.FILE_OPEN_PRE, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT, "<init>",
                "(Ljava/io/File;)V", InterceptPoint.FILE_OPEN_PRE, this);
        RuntimeSpy.registerInterceptor(FILE_OUTPUT, "write",
                "([BII)V", InterceptPoint.FILE_READ_PRE, this);
        log.debug("已注册 FileOutputStream 拦截点");
    }

    /**
     * 注册 RandomAccessFile 拦截。
     */
    private void registerRandomAccessIntercepts() {
        RuntimeSpy.registerInterceptor(RANDOM_FILE, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V", InterceptPoint.FILE_OPEN_PRE, this);
        RuntimeSpy.registerInterceptor(RANDOM_FILE, "read",
                "([B)I", InterceptPoint.FILE_READ_PRE, this);
        RuntimeSpy.registerInterceptor(RANDOM_FILE, "write",
                "([B)V", InterceptPoint.FILE_READ_PRE, this);
        log.debug("已注册 RandomAccessFile 拦截点");
    }

    /**
     * 添加文件操作记录。
     *
     * @param record 文件记录
     */
    public void addRecord(FileRecord record) {
        if (records.size() >= MAX_RECORDS) {
            records.remove(0);
        }
        records.add(record);
    }

    /**
     * 获取所有文件操作记录。
     *
     * @return 文件记录列表
     */
    public List<FileRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    /**
     * 获取最近 N 条文件操作记录。
     *
     * @param n 条数
     * @return 文件记录列表
     */
    public List<FileRecord> tail(int n) {
        int size = records.size();
        if (n >= size) {
            return getRecords();
        }
        return new ArrayList<>(records.subList(size - n, size));
    }

    /**
     * 清空文件操作记录。
     */
    public void clear() {
        records.clear();
    }

    /**
     * 文件操作记录。
     *
     * @author CH
     * @since 4.0.0.42
     */
    @Data
    public static class FileRecord {

        /**
         * 时间戳
         */
        private long timestamp;

        /**
         * 操作类型
         */
        private String operation;

        /**
         * 文件路径
         */
        private String path;

        /**
         * 读取字节数
         */
        private long bytesRead;

        /**
         * 写入字节数
         */
        private long bytesWritten;

        /**
         * 耗时（毫秒）
         */
        private long duration;

        /**
         * 状态
         */
        private String status;

        /**
         * 类名
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;
    }
}
