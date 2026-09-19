package com.chua.common.support.media.ffmpeg;

/**
 * ffmpeg 命令执行结果。
 *
 * <p>封装 FFmpeg 进程的执行状态、退出码、标准输出、错误输出和执行时间。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FFmpegResult {

    /** 是否执行成功 */
    private boolean success;

    /** 进程退出码，0 表示正常退出 */
    private int exitCode;

    /** 标准输出内容 */
    private String stdout;

    /** 错误输出内容 */
    private String stderr;

    /** 执行耗时（毫秒） */
    private long executionTime;

    /**
     * 是否成功
     *
     * @return 是否成功的结果
     */
    public boolean isSuccess() { return success; }
    /**
     * 设置成功
     *
     * @param success 成功
     */
    public void setSuccess(boolean success) { this.success = success; }
    /**
     * 获取exit编码
     *
     * @return 获取exit编码的结果
     */
    public int getExitCode() { return exitCode; }
    /**
     * 设置exit编码
     *
     * @param exitCode exit编码
     */
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
    /**
     * 获取Stdout
     *
     * @return 获取stdout的结果
     */
    public String getStdout() { return stdout; }
    /**
     * 设置Stdout
     *
     * @param stdout stdout
     */
    public void setStdout(String stdout) { this.stdout = stdout; }
    /**
     * 获取Stderr
     *
     * @return 获取stderr的结果
     */
    public String getStderr() { return stderr; }
    /**
     * 设置Stderr
     *
     * @param stderr stderr
     */
    public void setStderr(String stderr) { this.stderr = stderr; }
    /**
     * 获取执行时间
     *
     * @return 获取执行时间的结果
     */
    public long getExecutionTime() { return executionTime; }
    /**
     * 设置执行时间
     *
     * @param executionTime 执行时间
     */
    public void setExecutionTime(long executionTime) { this.executionTime = executionTime; }
}
