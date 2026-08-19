package com.chua.common.support.media.ffmpeg;

/**
 * FFmpeg 命令执行结果。
 *
 * <p>封装 FFmpeg 进程的执行状态、退出码、标准输出、错误输出和执行时间。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FFmpegResult {

    /** 是否执行成功 */
    /**
     * 是否成功
     */
    private boolean success;

    /** 进程退出码，0 表示正常退出 */
    private int exitCode;

    /** 标准输出内容 */
    private String stdout;

    /** 错误输出内容 */
    private String stderr;

    /** 执行耗时（毫秒） */
    private long executionTime;

    /** 是否Success */
    public boolean isSuccess() { return success; }
    /** 设置Success */
    public void setSuccess(boolean success) { this.success = success; }
    /** 获取ExitCode */
    public int getExitCode() { return exitCode; }
    /** 设置ExitCode */
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    /** 获取Stdout */
    public String getStdout() { return stdout; }
    /** 设置Stdout */
    public void setStdout(String stdout) { this.stdout = stdout; }
    /** 获取Stderr */
    public String getStderr() { return stderr; }
    /** 设置Stderr */
    public void setStderr(String stderr) { this.stderr = stderr; }
    /** 获取ExecutionTime */
    public long getExecutionTime() { return executionTime; }
    /** 设置ExecutionTime */
    public void setExecutionTime(long executionTime) { this.executionTime = executionTime; }
}
