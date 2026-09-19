package com.chua.common.support.lang.cmd;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.common.support.utils.IoUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.ThreadUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 基于 {@link ProcessBuilder} 的默认命令执行器实现。
 *
 * <p>作为 SPI 的默认实现（{@code @Spi("process")}, {@code @SpiDefault}），
 * 通过 Java 原生 {@link ProcessBuilder} 与 {@link Runtime} 机制执行系统命令。</p>
 *
 * <h3>特性：</h3>
 * <ul>
 *   <li>支持同步执行命令</li>
 *   <li>支持超时终止（超时后强制 {@link Process#destroyForcibly()}）</li>
 *   <li>支持异步执行（内部线程池）</li>
 *   <li>支持通过 {@link #setCharset(Charset)} 指定输出编码</li>
 *   <li>支持通过 {@link #setWorkDirectory(File)} 指定工作目录</li>
 *   <li>支持实时逐行输出（Windows 下优先 ConPTY，失败回退 ProcessBuilder）</li>
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
 * @since 4.0.0.42
 */
@SpiDefault
@Spi("process")
public class ProcessCmdExecutor implements CmdExecutor {

    /**
     * 默认的异步执行线程池核心线程数
     */
    private static final int DEFAULT_CORE_POOL_SIZE = 4;

    /**
     * 默认的异步执行线程池最大线程数
     */
    private static final int DEFAULT_MAX_POOL_SIZE = 8;

    /**
     * 默认的异步执行线程池空闲线程存活时间（秒）
     */
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    /**
     * 关闭线程池时的等待时间（秒）
     */
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    /**
     * 超时阈值：小于等于该值视为不超时
     */
    private static final long NO_TIMEOUT = 0L;

    /**
     * 初始退出码占位值
     */
    private static final int INITIAL_EXIT_CODE = -1;

    /**
     * 执行器 SPI 名称
     */
    private static final String EXECUTOR_NAME = "process";

    /**
     * 异步执行线程池线程名前缀
     */
    private static final String THREAD_NAME_PREFIX = "cmd-executor-";

    /**
     * 命令非空校验失败的异常消息
     */
    private static final String ERR_INVALID_COMMAND = "Command must not be null or empty";

    /**
     * 系统属性名：操作系统名称
     */
    private static final String OS_NAME_PROPERTY = "os.name";

    /**
     * 操作系统名称中包含的 Windows 关键字
     */
    private static final String OS_NAME_WIN_KEYWORD = "win";

    /**
     * Windows 命令解释器路径
     */
    private static final String CMD_EXE = "cmd.exe";

    /**
     * cmd.exe 的 /c 参数：执行后退出
     */
    private static final String CMD_C_FLAG = "/c";

    /**
     * Unix Shell 路径
     */
    private static final String SH_PATH = "/bin/sh";

    /**
     * Shell 的 -c 参数：执行命令字符串
     */
    private static final String SH_C_FLAG = "-c";

    /**
     * 实时输出流读取异常时的错误上下文标识
     */
    private static final String STREAM_ERROR_CONTEXT = "stream";

    /**
     * 异步执行线程池
     */
    private final ExecutorService executorService;

    /**
     * 输出编码
     */
    private Charset charset;

    /**
     * 工作目录
     */
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
                ThreadUtils.newDaemonThreadFactory(THREAD_NAME_PREFIX),
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
    /**
     * 获取Name
    */
    public String getName() {
        return EXECUTOR_NAME;
    }

    // ==================== 同步执行 ====================

    @Override
    /**
     * 执行
    */
    public CmdResult execute(String command) {
        return doExecute(command, NO_TIMEOUT, null);
    }

    @Override
    /**
     * 执行
    */
    public CmdResult execute(String command, long timeout, TimeUnit unit) {
        return doExecute(command, timeout, unit);
    }

    @Override
    /**
     * 执行
    */
    public CmdResult execute(String[] command) {
        return doExecuteArray(command, NO_TIMEOUT, null);
    }

    @Override
    /**
     * 执行
    */
    public CmdResult execute(String[] command, long timeout, TimeUnit unit) {
        return doExecuteArray(command, timeout, unit);
    }

    @Override
    /**
     * 执行（扩展参数：工作目录/环境变量/标准输入）
    */
    public CmdResult execute(String[] command, long timeout, TimeUnit unit,
                             File workingDirectory, Map<String, String> environment, String input) {
        long startTime = System.currentTimeMillis();
        if (command == null || command.length == 0) {
            return errorResult(joinCommand(command), startTime, new IllegalArgumentException(ERR_INVALID_COMMAND));
        }
        return executeInternal(command, joinCommand(command), timeout, unit, startTime,
                workingDirectory, environment, input);
    }

    @Override
    /**
     * 执行WithOutput（扩展参数）
    */
    public CmdResult executeWithOutput(String[] command, long timeout, TimeUnit unit, LineCallback callback,
                                       File workingDirectory, Map<String, String> environment, String input) {
        long startTime = System.currentTimeMillis();
        return executeWithOutputInternal(command, joinCommand(command), timeout, unit, callback, startTime,
                workingDirectory, environment, input);
    }

    @Override
    /**
     * 执行Async（扩展参数）
    */
    public void executeAsync(String[] command, long timeout, TimeUnit unit, CmdCallback callback,
                             File workingDirectory, Map<String, String> environment, String input) {
        String displayCommand = joinCommand(command);
        submitAsync(displayCommand, timeout, unit, callback, () ->
                executeInternal(command, displayCommand, timeout, unit, System.currentTimeMillis(),
                        workingDirectory, environment, input));
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
            return errorResult(command, startTime, new IllegalArgumentException(ERR_INVALID_COMMAND));
        }
        return executeInternal(parseCommand(command), command, timeout, unit, startTime);
    }

    /**
     * 数组形式的执行入口：跳过命令字符串解析，直接把参数交给 {@link ProcessBuilder}。
     *
     * @param command 程序名与参数数组
     * @param timeout 超时值（≤0 表示不超时）
     * @param unit    超时单位
     * @return 执行结果
     */
    private CmdResult doExecuteArray(String[] command, long timeout, TimeUnit unit) {
        long startTime = System.currentTimeMillis();

        if (command == null || command.length == 0) {
            return errorResult(joinCommand(command), startTime, new IllegalArgumentException(ERR_INVALID_COMMAND));
        }
        return executeInternal(command, joinCommand(command), timeout, unit, startTime);
    }

    /**
     * 命令执行的主体逻辑，同步执行与数组执行共用。
     *
     * @param cmdArray       已解析好的参数数组
     * @param displayCommand 用于结果展示与日志的命令字符串
     * @param timeout        超时值（≤0 表示不超时）
     * @param unit           超时单位
     * @param startTime      起始时间戳
     * @return 执行结果
     */
    private CmdResult executeInternal(String[] cmdArray, String displayCommand,
                                      long timeout, TimeUnit unit, long startTime) {
        return executeInternal(cmdArray, displayCommand, timeout, unit, startTime, null, null, null);
    }

    /**
     * 命令执行的主体逻辑，同步执行与数组执行共用，支持扩展参数。
     *
     * @param cmdArray         已解析好的参数数组
     * @param displayCommand   用于结果展示与日志的命令字符串
     * @param timeout          超时值（≤0 表示不超时）
     * @param unit             超时单位
     * @param startTime        起始时间戳
     * @param workingDirectory 请求级工作目录，null 时回退到实例级工作目录
     * @param environment      附加环境变量，可为 null
     * @param input            标准输入内容，可为 null
     * @return 执行结果
     */
    private CmdResult executeInternal(String[] cmdArray, String displayCommand,
                                      long timeout, TimeUnit unit, long startTime,
                                      File workingDirectory, Map<String, String> environment, String input) {
        try {
            // 构建 ProcessBuilder 并设置工作目录与流合并策略
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            if (workingDirectory != null) {
                pb.directory(workingDirectory);
            } else if (workDirectory != null) {
                pb.directory(workDirectory);
            }
            if (environment != null && !environment.isEmpty()) {
                pb.environment().putAll(environment);
            }
            pb.redirectErrorStream(false);

            // 启动进程
            Process process = pb.start();

            // 写入标准输入
            if (input != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(input.getBytes(charset));
                } catch (IOException ignored) {
                    // 进程可能已提前退出，忽略写输入异常
                }
            }

            // 异步读取标准输出与错误输出
            StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream(), charset);
            StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream(), charset);
            stdoutGobbler.start();
            stderrGobbler.start();

            boolean timedOut = false;

            if (timeout > NO_TIMEOUT && unit != null) {
                // 带超时等待进程结束
                timedOut = !process.waitFor(timeout, unit);
                if (timedOut) {
                    process.destroyForcibly();
                }
            } else {
                // 无限等待进程结束
                process.waitFor();
            }

            // 等待输出流读取完成
            stdoutGobbler.join();
            stderrGobbler.join();

            long endTime = System.currentTimeMillis();
            int exitCode = timedOut ? CmdResult.EXIT_CODE_TIMEOUT : process.exitValue();

            return CmdResult.builder()
                    .exitCode(exitCode)
                    .stdout(stdoutGobbler.getContent())
                    .stderr(stderrGobbler.getContent())
                    .command(displayCommand)
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeout(timedOut)
                    .build();

        } catch (IOException e) {
            return errorResult(displayCommand, startTime, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult(displayCommand, startTime, e);
        }
    }

    /**
     * 构建执行异常的结果对象。
     *
     * @param command   命令字符串
     * @param startTime 起始时间戳
     * @param throwable 异常对象
     * @return 错误结果
     */
    private static CmdResult errorResult(String command, long startTime, Throwable throwable) {
        return CmdResult.builder()
                .exitCode(CmdResult.EXIT_CODE_ERROR)
                .command(command)
                .startTime(startTime)
                .endTime(System.currentTimeMillis())
                .throwable(throwable)
                .build();
    }

    /**
     * 将参数数组拼接为命令字符串，仅用于结果展示与日志。
     *
     * @param command 参数数组
     * @return 拼接后的命令字符串
     */
    private static String joinCommand(String[] command) {
        if (command == null || command.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (arg == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(arg);
        }
        return sb.toString();
    }

    // ==================== 异步执行 ====================

    @Override
    /**
     * 执行Async
    */
    public void executeAsync(String command, CmdCallback callback) {
        doExecuteAsync(command, NO_TIMEOUT, null, callback);
    }

    @Override
    /**
     * 执行Async
    */
    public void executeAsync(String command, long timeout, TimeUnit unit, CmdCallback callback) {
        doExecuteAsync(command, timeout, unit, callback);
    }

    @Override
    /**
     * 执行Async
    */
    public void executeAsync(String[] command, CmdCallback callback) {
        doExecuteArrayAsync(command, NO_TIMEOUT, null, callback);
    }

    @Override
    /**
     * 执行Async
    */
    public void executeAsync(String[] command, long timeout, TimeUnit unit, CmdCallback callback) {
        doExecuteArrayAsync(command, timeout, unit, callback);
    }

    /**
     * 异步执行逻辑
     *
     * @param command  待执行命令
     * @param timeout  超时值（≤0 表示不超时）
     * @param unit     超时单位
     * @param callback 执行回调，允许为空
     */
    private void doExecuteAsync(String command, long timeout, TimeUnit unit, CmdCallback callback) {
        submitAsync(command, timeout, unit, callback, () -> doExecute(command, timeout, unit));
    }

    /**
     * 数组形式的异步执行入口：跳过命令字符串解析。
     *
     * @param command  程序名与参数数组
     * @param timeout  超时值（≤0 表示不超时）
     * @param unit     超时单位
     * @param callback 执行回调，允许为空
     */
    private void doExecuteArrayAsync(String[] command, long timeout, TimeUnit unit, CmdCallback callback) {
        String displayCommand = joinCommand(command);
        submitAsync(displayCommand, timeout, unit, callback, () -> doExecuteArray(command, timeout, unit));
    }

    /**
     * 提交异步执行任务的公共逻辑，字符串与数组两种入口共用。
     *
     * @param displayCommand 用于回调通知的命令字符串
     * @param timeout        超时值（≤0 表示不超时）
     * @param unit           超时单位
     * @param callback       执行回调，允许为空
     * @param task           实际执行动作
     */
    private void submitAsync(String displayCommand, long timeout, TimeUnit unit,
                             CmdCallback callback, Supplier<CmdResult> task) {
        CmdCallback finalCallback = callback != null ? callback : new CmdCallback() {
            @Override
            /**
             * OnComplete
            */
            public void onComplete(CmdResult result) {
                // 空操作：未指定回调时静默完成
            }
        };

        executorService.submit(() -> {
            try {
                finalCallback.onStart(displayCommand);
                CmdResult result = task.get();
                if (result.isTimeout()) {
                    finalCallback.onTimeout(displayCommand, timeout, unit);
                }
                finalCallback.onComplete(result);
            } catch (Throwable t) {
                finalCallback.onError(displayCommand, t);
            }
        });
    }

    // ==================== 实时输出执行 ====================

    @Override
    /**
     * 执行WithOutput
    */
    public CmdResult executeWithOutput(String command, long timeout, TimeUnit unit, LineCallback callback) {
        long startTime = System.currentTimeMillis();

        if (StringUtils.isNullOrEmpty(command)) {
            return invalidCommandResult(command, startTime, callback);
        }
        return executeWithOutputInternal(parseCommand(command), command, timeout, unit, callback, startTime);
    }

    @Override
    /**
     * 执行WithOutput
    */
    public CmdResult executeWithOutput(String[] command, long timeout, TimeUnit unit, LineCallback callback) {
        long startTime = System.currentTimeMillis();

        if (command == null || command.length == 0) {
            return invalidCommandResult(joinCommand(command), startTime, callback);
        }
        return executeWithOutputInternal(command, joinCommand(command), timeout, unit, callback, startTime);
    }

    /**
     * 构建空命令的失败结果并通知回调。
     *
     * @param command   命令字符串
     * @param startTime 起始时间戳
     * @param callback  逐行回调
     * @return 错误结果
     */
    private static CmdResult invalidCommandResult(String command, long startTime, LineCallback callback) {
        CmdResult result = errorResult(command, startTime, new IllegalArgumentException(ERR_INVALID_COMMAND));
        callback.onError(command, result.getThrowable());
        return result;
    }

    /**
     * 实时输出执行的主体逻辑，字符串与数组两种入口共用。
     *
     * @param cmdArray       已解析好的参数数组
     * @param displayCommand 用于结果展示与回调通知的命令字符串
     * @param timeout        超时值（≤0 表示不超时）
     * @param unit           超时单位
     * @param callback       逐行回调
     * @param startTime      起始时间戳
     * @return 执行结果
     */
    private CmdResult executeWithOutputInternal(String[] cmdArray, String displayCommand,
                                                long timeout, TimeUnit unit,
                                                LineCallback callback, long startTime) {
        try {
            // 优先使用 ConPTY（Windows 10 1809+ 支持进度条与 ANSI 转义）
            if (WindowsConPtyProcess.isAvailable() && WindowsConPtyProcess.isWindows()) {
                return doExecuteWithOutputConPty(cmdArray, displayCommand, workDirectory,
                        charset, timeout, unit, callback, startTime);
            }

            // 回退到标准 ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            if (workDirectory != null) {
                pb.directory(workDirectory);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            LineStreamGobbler outputGobbler = new LineStreamGobbler(process.getInputStream(), charset, callback);
            outputGobbler.start();

            boolean timedOut = false;
            if (timeout > NO_TIMEOUT && unit != null) {
                // 带超时等待进程结束
                timedOut = !process.waitFor(timeout, unit);
                if (timedOut) {
                    process.destroyForcibly();
                }
            } else {
                // 无限等待进程结束
                process.waitFor();
            }

            outputGobbler.join();

            long endTime = System.currentTimeMillis();
            int exitCode = timedOut ? CmdResult.EXIT_CODE_TIMEOUT : process.exitValue();

            CmdResult result = CmdResult.builder()
                    .exitCode(exitCode)
                    .stdout(outputGobbler.getContent())
                    .command(displayCommand)
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeout(timedOut)
                    .build();

            callback.onComplete(exitCode);
            return result;

        } catch (Exception e) {
            CmdResult result = errorResult(displayCommand, startTime, e);
            callback.onError(displayCommand, e);
            return result;
        }
    }

    /**
     * 实时输出执行（扩展参数版本）。
     *
     * <p>指定工作目录、环境变量或标准输入时直接走标准 {@link ProcessBuilder}，
     * 不启用 ConPTY（其封装不支持扩展参数的透传）。</p>
     */
    private CmdResult executeWithOutputInternal(String[] cmdArray, String displayCommand,
                                                long timeout, TimeUnit unit,
                                                LineCallback callback, long startTime,
                                                File workingDirectory, Map<String, String> environment,
                                                String input) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdArray);
            if (workingDirectory != null) {
                pb.directory(workingDirectory);
            } else if (workDirectory != null) {
                pb.directory(workDirectory);
            }
            if (environment != null && !environment.isEmpty()) {
                pb.environment().putAll(environment);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            if (input != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(input.getBytes(charset));
                } catch (IOException ignored) {
                    // 进程可能已提前退出，忽略写输入异常
                }
            }

            LineStreamGobbler outputGobbler = new LineStreamGobbler(process.getInputStream(), charset, callback);
            outputGobbler.start();

            boolean timedOut = false;
            if (timeout > NO_TIMEOUT && unit != null) {
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
                    .command(displayCommand)
                    .startTime(startTime)
                    .endTime(endTime)
                    .timeout(timedOut)
                    .build();

            callback.onComplete(exitCode);
            return result;

        } catch (Exception e) {
            CmdResult result = errorResult(displayCommand, startTime, e);
            callback.onError(displayCommand, e);
            return result;
        }
    }

    // ==================== ConPTY 执行 ====================

    /**
     * 使用 Windows ConPTY 执行命令，支持进度条与 ANSI 转义序列
     *
     * @param cmdArray   命令参数数组
     * @param command    原始命令字符串
     * @param workDir    工作目录
     * @param charset    输出编码
     * @param timeout    超时值（≤0 表示不超时）
     * @param unit       超时单位
     * @param callback   逐行回调
     * @param startTime  起始时间戳
     * @return 执行结果
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
            int exitCode = INITIAL_EXIT_CODE;
            try {
                if (timeout > NO_TIMEOUT && unit != null) {
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
            // ConPTY 启动失败时回调错误并返回错误结果
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
    /**
     * 关闭
    */
    public void close() throws Exception {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
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
     * <p>在 Windows 上使用 {@code cmd.exe /c}，在 Unix 上使用 {@code /bin/sh -c}。</p>
     *
     * @param command 命令字符串
     * @return 命令参数数组
     */
    static String[] parseCommand(String command) {
        if (StringUtils.isNullOrEmpty(command)) {
            return new String[0];
        }

        String osName = System.getProperty(OS_NAME_PROPERTY, "").toLowerCase();
        if (osName.contains(OS_NAME_WIN_KEYWORD)) {
            return new String[]{CMD_EXE, CMD_C_FLAG, command};
        } else {
            return new String[]{SH_PATH, SH_C_FLAG, command};
        }
    }

    /**
     * 流读取线程，用于异步读取进程的标准输出或错误输出
     */
    static class StreamGobbler extends Thread {
        /**
         * 待读取的输入流
         */
        private final InputStream inputStream;

        /**
         * 输出字符集
         */
        private final Charset charset;

        /**
         * 已读取并拼接的完整内容
         */
        private volatile String content;

        StreamGobbler(InputStream inputStream, Charset charset) {
            this.inputStream = inputStream;
            this.charset = charset;
            this.content = "";
            setDaemon(true);
        }

        @Override
        /**
         * 运行
        */
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
     * 逐行读取流并回调的线程，用于实时输出场景
     */
    static class LineStreamGobbler extends Thread {
        /**
         * 待读取的输入流
         */
        private final InputStream inputStream;

        /**
         * 输出字符集
         */
        private final Charset charset;

        /**
         * 逐行回调
         */
        private final LineCallback callback;

        /**
         * 已读取并拼接的完整内容
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
        /**
         * 运行
        */
        public void run() {
            try (InputStreamReader reader = new InputStreamReader(inputStream, charset)) {
                StringBuilder buf = new StringBuilder();
                int cursor = 0;
                int ch;
                while ((ch = reader.read()) != -1) {
                    if (ch == '\r') {
                        cursor = 0;
                    } else if (ch == '\b') {
                        if (cursor > 0) {
                            cursor--;
                        }
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
                callback.onError(STREAM_ERROR_CONTEXT, e);
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        }

        String getContent() {
            return content.toString();
        }
    }
}
