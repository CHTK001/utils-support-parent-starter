package com.chua.common.support.lang.cmd;

import java.util.concurrent.TimeUnit;

/**
 * 命令执行结果，封装命令执行后的输出、退出码和耗时等信息。
 *
 * <p>该类的实例通过 {@link CmdResultBuilder} 创建，一旦构建完成即为不可变对象。
 *
 * <h3>包含的信息：</h3>
 * <ul>
 *   <li>{@code exitCode} — 进程退出码（0 通常表示成功）</li>
 *   <li>{@code stdout} — 标准输出内容</li>
 *   <li>{@code stderr} — 错误输出内容</li>
 *   <li>{@code command} — 执行的命令字符串</li>
 *   <li>{@code startTime} / {@code endTime} — 执行起止时间戳（毫秒）</li>
 *   <li>{@code timeout} — 是否因超时而终止</li>
 *   <li>{@code throwable} — 执行过程中抛出的异常（如果有）</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/15
 */
public class CmdResult {

    /** 退出码：超时标记 */
    public static final int EXIT_CODE_TIMEOUT = -1;
    /** 退出码：未知错误 */
    public static final int EXIT_CODE_ERROR = -2;

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final String command;
    /**
     * 开始时间
     */
    private final long startTime;
    /**
     * 结束时间
     */
    private final long endTime;
    /**
     * 超时时间（毫秒）
     */
    private final boolean timeout;
    private final Throwable throwable;

    CmdResult(int exitCode, String stdout, String stderr, String command,
              long startTime, long endTime, boolean timeout, Throwable throwable) {
        this.exitCode = exitCode;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
        this.command = command;
        this.startTime = startTime;
        this.endTime = endTime;
        this.timeout = timeout;
        this.throwable = throwable;
    }

    /**
     * 创建结果构建器
     *
     * @return CmdResultBuilder 实例
     */
    public static CmdResultBuilder builder() {
        return new CmdResultBuilder();
    }

    /**
     * 获取进程退出码
     *
     * @return 退出码，-1 表示超时，-2 表示执行异常
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * 判断命令是否执行成功（退出码为 0）
     *
     * @return 成功返回 true
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }

    /**
     * 获取标准输出内容
     *
     * @return stdout 字符串
     */
    public String getStdout() {
        return stdout;
    }

    /**
     * 获取错误输出内容
     *
     * @return stderr 字符串
     */
    public String getStderr() {
        return stderr;
    }

    /**
     * 获取执行的命令
     *
     * @return 命令字符串
     */
    public String getCommand() {
        return command;
    }

    /**
     * 获取执行开始时间戳（毫秒）
     *
     * @return 开始时间
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * 获取执行结束时间戳（毫秒）
     *
     * @return 结束时间
     */
    public long getEndTime() {
        return endTime;
    }

    /**
     * 获取执行耗时（毫秒）
     *
     * @return 耗时毫秒数
     */
    public long getDuration() {
        return endTime - startTime;
    }

    /**
     * 是否因超时而终止
     *
     * @return 超时返回 true
     */
    public boolean isTimeout() {
        return timeout;
    }

    /**
     * 获取执行过程中的异常（如果有）
     *
     * @return 异常对象，无异常则返回 null
     */
    public Throwable getThrowable() {
        return throwable;
    }

    @Override
    public String toString() {
        return "CmdResult{" +
                "command='" + command + '\'' +
                ", exitCode=" + exitCode +
                ", duration=" + getDuration() + "ms" +
                ", timeout=" + timeout +
                ", stdout='" + truncate(stdout, 100) + '\'' +
                ", stderr='" + truncate(stderr, 100) + '\'' +
                '}';
    }

    private static String truncate(String str, int max) {
        if (str == null || str.length() <= max) {
            return str;
        }
        return str.substring(0, max) + "...";
    }

    /**
     * CmdResult 构建器
     */
    public static class CmdResultBuilder {
        private int exitCode;
        private String stdout;
        private String stderr;
        private String command;
        /**
         * 开始时间
         */
        private long startTime;
        /**
         * 结束时间
         */
        private long endTime;
        /**
         * 超时时间（毫秒）
         */
        private boolean timeout;
        private Throwable throwable;

        CmdResultBuilder() {}

        public CmdResultBuilder exitCode(int exitCode) {
            this.exitCode = exitCode;
            return this;
        }

        public CmdResultBuilder stdout(String stdout) {
            this.stdout = stdout;
            return this;
        }

        public CmdResultBuilder stderr(String stderr) {
            this.stderr = stderr;
            return this;
        }

        public CmdResultBuilder command(String command) {
            this.command = command;
            return this;
        }

        public CmdResultBuilder startTime(long startTime) {
            this.startTime = startTime;
            return this;
        }

        public CmdResultBuilder endTime(long endTime) {
            this.endTime = endTime;
            return this;
        }

        public CmdResultBuilder timeout(boolean timeout) {
            this.timeout = timeout;
            return this;
        }

        public CmdResultBuilder throwable(Throwable throwable) {
            this.throwable = throwable;
            return this;
        }

        public CmdResult build() {
            return new CmdResult(exitCode, stdout, stderr, command, startTime, endTime, timeout, throwable);
        }
    }
}
