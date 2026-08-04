package com.chua.common.support.lang.cmd;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.common.support.utils.IoUtils;
import com.chua.common.support.utils.StringUtils;

import java.io.*;
import java.nio.charset.Charset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NullUnmarked;

/**
 * 基于 {@link ProcessBuilder} 的默认命令执行器实现。
 *
 * <p>作为 SPI 的默认实现（{@code @Spi("process")}, {@code @SpiDefault}），
 * 通过 Java 原生 {@link ProcessBuilder} 和 {@link Runtime} 机制执行系统命令。
 *
 * <h3>特性：</h3>
 * <ul>
 *   <li>支持同步执行命令</li>
 *   <li>支持超时终止（超时后强制 {@link Process#destroyForcibly()}）</li>
 *   <li>支持异步执行（内部线程池）</li>
 *   <li>支持通过 {@link #setCharset(Charset)} 指定输出编码</li>
 *   <li>支持通过 {@link #setWorkDirectory(File)} 指定工作目录</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * CmdExecutor executor = new ProcessCmdExecutor();
 * CmdResult result = executor.execute("echo Hello World");
 * System.out.println(result.getStdout());  // "Hello World"
 * }</pre>
 *
 * @author CH
 * @since 2026/07/15
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@SpiDefault
@Spi("process")
public class ProcessCmdExecutor implements CmdExecutor {

    /** 默认的异步执行线程池核心线程数 */
    private static final int DEFAULT_CORE_POOL_SIZE = 4;
    /** 默认的异步执行线程池最大线程数 */
    private static final int DEFAULT_MAX_POOL_SIZE = 8;
    /** 默认的异步执行线程池空闲线程存活时间（秒） */
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    /** 异步执行线程池 */
    private final ExecutorService executorService;
    /** 输出编码 */
    /**
     * 字符集
     */
    private Charset charset;
    /** 工作目录 */
    private File workDirectory;

    /**
     * 使用默认配置创建执行器
     */
    public ProcessCmdExecutor() {
        this(Charset.defaultCharset(), null);
    }

    /**
     * 创建执行器并指定输出编码
     *
     * @param charset 命令输出编码
     */
    public ProcessCmdExecutor(Charset charset) {
        this(charset, null);
    }

    /**
     * 创建执行器并指定工作目录和输出编码
     *
     * @param charset       命令输出编码
     * @param workDirectory 工作目录
     */
    public ProcessCmdExecutor(Charset charset, File workDirectory) {
        this.charset = charset != null ? charset : Charset.defaultCharset();
        this.workDirectory = workDirectory;
        this.executorService = new ThreadPoolExecutor(
                DEFAULT_CORE_POOL_SIZE,
                DEFAULT_MAX_POOL_SIZE,
                DEFAULT_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    private final AtomicBoolean daemon = new AtomicBoolean(true);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "cmd-executor-" + daemon.getAndSet(true));
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 设置输出编码
     *
     * @param charset 编码
     */
    public void setCharset(Charset charset) {
        this.charset = charset != null ? charset : Charset.defaultCharset();
    }

    /**
     * 设置工作目录
     *
     * @param workDirectory 工作目录
     */
    public void setWorkDirectory(File workDirectory) {
        this.workDirectory = workDirectory;
    }

    @Override
    public String getName() {
        return "process";
    }

    // ==================== 同步执行 ====================

    @Override
    public CmdResult execute(String command) {
        return doExecute(command, 0, null);
    }

    @Override
    public CmdResult execute(String command, long timeout, TimeUnit unit) {
        return doExecute(command, timeout, unit);
    }

    /**
     * 实际执行逻辑
     *
     * @param command 命令字符串
     * @param timeout 超时值（≤0 表示不超时）
     * @param unit    超时单位
     * @return 执行结果
     */
    private CmdResult doExecute(String command, long timeout, TimeUnit unit) {
        long startTime = System.currentTimeMillis();

        if (StringUtils.isNullOrEmpty(command)) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(command)
                    .startTime(startTime)
                    .endTime(System.currentTimeMillis())
                    .throwable(new IllegalArgumentException("Command must not be null or empty"))
                    .build();
        }

        try {
            // 解析命令
            String[] cmdArray = parseCommand(command);

            // 创建 ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            if (workDirectory != null) {
                pb.directory(workDirectory);
            }
            pb.redirectErrorStream(false);

            // 启动进程
            Process process = pb.start();

            // 异步读取输出流
            StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream(), charset);
            StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream(), charset);
            stdoutGobbler.start();
            stderrGobbler.start();

            boolean timedOut = false;

            if (timeout > 0 && unit != null) {
                // 带超时等待
                timedOut = !process.waitFor(timeout, unit);
                if (timedOut) {
                    process.destroyForcibly();
                }
            } else {
                // 无限等待
                process.waitFor();
            }

            // 等待流读取完成（进程已结束，流会迅速读完）
            stdoutGobbler.join();
            stderrGobbler.join();

            long endTime = System.currentTimeMillis();
            int exitCode = timedOut ? CmdResult.EXIT_CODE_TIMEOUT : process.exitValue();

            return CmdResult.builder()
                    .exitCode(exitCode)
                    .stdout(stdoutGobbler.getContent())
                    .stderr(stderrGobbler.getContent())
                    .command(command)
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeout(timedOut)
                    .build();

        } catch (IOException e) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(command)
                    .startTime(startTime)
                    .endTime(System.currentTimeMillis())
                    .throwable(e)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(command)
                    .startTime(startTime)
                    .endTime(System.currentTimeMillis())
                    .throwable(e)
                    .build();
        }
    }

    // ==================== 异步执行 ====================

    @Override
    public void executeAsync(String command, CmdCallback callback) {
        doExecuteAsync(command, 0, null, callback);
    }

    @Override
    public void executeAsync(String command, long timeout, TimeUnit unit, CmdCallback callback) {
        doExecuteAsync(command, timeout, unit, callback);
    }

    /**
     * 异步执行逻辑
     */
    private void doExecuteAsync(String command, long timeout, TimeUnit unit, CmdCallback callback) {
        if (callback == null) {
            callback = new CmdCallback() {
                @Override
                public void onComplete(CmdResult result) {}
            };
        }

        CmdCallback finalCallback = callback;
        executorService.submit(() -> {
            try {
                finalCallback.onStart(command);
                CmdResult result = doExecute(command, timeout, unit);
                if (result.isTimeout()) {
                    finalCallback.onTimeout(command, timeout, unit);
                }
                finalCallback.onComplete(result);
            } catch (Throwable t) {
                finalCallback.onError(command, t);
            }
        });
    }

    // ==================== 实时输出执行 ====================

    @Override
    public CmdResult executeWithOutput(String command, long timeout, TimeUnit unit, LineCallback callback) {
        long startTime = System.currentTimeMillis();

        if (StringUtils.isNullOrEmpty(command)) {
            CmdResult result = CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(command)
                    .startTime(startTime)
                    .endTime(System.currentTimeMillis())
                    .throwable(new IllegalArgumentException("Command must not be null or empty"))
                    .build();
            callback.onError(command, result.getThrowable());
            return result;
        }

        try {
            String[] cmdArray = parseCommand(command);

            // 尝试使用 ConPTY（Windows 10 1809+ 上支持进度条）
            if (WindowsConPtyProcess.isAvailable() && WindowsConPtyProcess.isWindows()) {
                return doExecuteWithOutputConPty(cmdArray, command, workDirectory,
                        charset, timeout, unit, callback, startTime);
            }

            // 回退到 ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            if (workDirectory != null) {
                pb.directory(workDirectory);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            LineStreamGobbler outputGobbler = new LineStreamGobbler(process.getInputStream(), charset, callback);
            outputGobbler.start();

            boolean timedOut = false;
            if (timeout > 0 && unit != null) {
                timedOut = !process.waitFor(timeout, unit);
                if (timedOut) {
                    process.destroyForcibly();
                }
            } else {
                process.waitFor();
            }

            outputGobbler.join();

            long endTime = System.currentTimeMillis();
            int exitCode = timedOut ? CmdResult.EXIT_CODE_TIMEOUT : process.exitValue();

            CmdResult result = CmdResult.builder()
                    .exitCode(exitCode)
                    .stdout(outputGobbler.getContent())
                    .command(command)
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeout(timedOut)
                    .build();

            callback.onComplete(exitCode);
            return result;

        } catch (Exception e) {
            CmdResult result = CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(command)
                    .startTime(startTime)
                    .endTime(System.currentTimeMillis())
                    .throwable(e)
                    .build();
            callback.onError(command, e);
            return result;
        }
    }

    // ==================== ConPTY 执行 ====================

    /**
     * 使用 Windows ConPTY 执行命令（支持进度条和 ANSI 转义）
     */
    private CmdResult doExecuteWithOutputConPty(
            String[] cmdArray, String command, File workDir,
            Charset charset, long timeout, TimeUnit unit,
            LineCallback callback, long startTime) {

        try {
            String workDirStr = (workDir != null) ? workDir.getAbsolutePath() : null;
            WindowsConPtyProcess conPty = WindowsConPtyProcess.start(cmdArray, workDirStr);

            LineStreamGobbler outputGobbler = new LineStreamGobbler(
                    conPty.getInputStream(), charset, callback);
            outputGobbler.start();

            boolean timedOut = false;
            int exitCode = -1;
            try {
                if (timeout > 0 && unit != null) {
                    long timeoutMs = unit.toMillis(timeout);
                    timedOut = !conPty.waitFor(timeoutMs);
                }
                if (!timedOut) {
                    exitCode = conPty.waitFor();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                timedOut = false;
                conPty.close();
                CmdResult result = CmdResult.builder()
                        .exitCode(CmdResult.EXIT_CODE_ERROR)
                        .command(command)
                        .startTime(startTime)
                        .endTime(System.currentTimeMillis())
                        .throwable(e)
                        .build();
                callback.onError(command, e);
                return result;
            } finally {
                outputGobbler.join();
                conPty.close();
            }

            if (timedOut) {
                exitCode = CmdResult.EXIT_CODE_TIMEOUT;
            }

            long endTime = System.currentTimeMillis();
            CmdResult result = CmdResult.builder()
                    .exitCode(exitCode)
                    .stdout(outputGobbler.getContent())
                    .command(command)
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeout(timedOut)
                    .build();

            callback.onComplete(exitCode);
            return result;

        } catch (Exception e) {
            // ConPTY 失败时回退到 ProcessBuilder
            CmdResult result = CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(command)
                    .startTime(startTime)
                    .endTime(System.currentTimeMillis())
                    .throwable(e)
                    .build();
            callback.onError(command, e);
            return result;
        }
    }

    // ==================== 资源释放 ====================

    @Override
    public void close() throws Exception {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 解析命令字符串为字符串数组
     *
     * <p>在 Windows 上使用 {@code cmd.exe /c}，在 Unix 上使用 {@code /bin/sh -c}。
     */
    static String[] parseCommand(String command) {
        if (command == null || command.isEmpty()) {
            return new String[0];
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return new String[]{"cmd.exe", "/c", command};
        } else {
            return new String[]{"/bin/sh", "-c", command};
        }
    }

    /**
     * 流读取线程，用于异步读取进程的标准输出或错误输出
     */
    static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        /**
         * 字符集
         */
        private final Charset charset;
        /**
         * 内容
         */
        private volatile String content;

        StreamGobbler(InputStream inputStream, Charset charset) {
            this.inputStream = inputStream;
            this.charset = charset;
            this.content = "";
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                content = IoUtils.asString(inputStream, charset);
            } catch (Exception e) {
                content = "";
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        }

        String getContent() {
            return content != null ? content : "";
        }
    }

    /**
     * 逐行读取流并回调的线程，用于实时输出
     */
    static class LineStreamGobbler extends Thread {
        private final InputStream inputStream;
        /**
         * 字符集
         */
        private final Charset charset;
        private final LineCallback callback;
        /**
         * 内容
         */
        private final StringBuilder content;

        LineStreamGobbler(InputStream inputStream, Charset charset, LineCallback callback) {
            this.inputStream = inputStream;
            this.charset = charset;
            this.callback = callback;
            this.content = new StringBuilder();
            setDaemon(true);
        }

        @Override
        public void run() {
            try (InputStreamReader reader = new InputStreamReader(inputStream, charset)) {
                StringBuilder buf = new StringBuilder();
                int cursor = 0;
                int ch;
                while ((ch = reader.read()) != -1) {
                    if (ch == '\r') {
                        cursor = 0;
                    } else if (ch == '\b') {
                        if (cursor > 0) cursor--;
                    } else if (ch == '\n') {
                        String line = buf.toString();
                        content.append(line).append('\n');
                        callback.onLine(line);
                        buf.setLength(0);
                        cursor = 0;
                    } else {
                        if (cursor < buf.length()) {
                            buf.setCharAt(cursor, (char) ch);
                        } else {
                            buf.append((char) ch);
                        }
                        cursor++;
                    }
                }
                if (buf.length() > 0) {
                    String line = buf.toString();
                    content.append(line).append('\n');
                    callback.onLine(line);
                }
            } catch (Exception e) {
                callback.onError("stream", e);
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        }

        String getContent() {
            return content.toString();
        }
    }
}
