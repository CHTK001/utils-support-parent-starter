package com.chua.common.support.media.ffmpeg;

/**
 * FFmpeg 命令执行结果。
 *
 * <p>封装 FFmpeg 进程的执行状态、退出码、标准输出、错误输出和执行时间。</p>
 *
 * @author CH
 * @since 1.0.0
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

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }
    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }
    public long getExecutionTime() { return executionTime; }
    public void setExecutionTime(long executionTime) { this.executionTime = executionTime; }
}
