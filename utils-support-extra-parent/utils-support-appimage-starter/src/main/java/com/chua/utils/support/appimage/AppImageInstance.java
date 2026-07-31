package com.chua.utils.support.appimage;

import lombok.Data;

import java.io.File;

/**
 * AppImage 实例 POJO，记录单个 AppImage 的运行时信息
 *
 * @author CH
 */
@Data
public class AppImageInstance {

    /*
     * 实例唯一标识
     */
    private String id;

    /*
     * 应用名称
     */
    private String appName;

    /*
     * 对应的 AppImage 文件
     */
    private File appImageFile;

    /*
     * AppImage 工作目录
     */
    private File workDir;

    /*
     * 进程 PID
     */
    private long pid;

    /*
     * 日志文件
     */
    private File logFile;

    /*
     * 运行状态，默认已停止
     */
    private AppImageStatus status = AppImageStatus.STOPPED;

    /*
     * 运行时配置
     */
    private AppImageRuntimeConfig runtimeConfig;

    /*
     * 启动时间
     */
    private long startTime;

    /*
     * 停止时间
     */
    private long stopTime;

    /*
     * 重启次数
     */
    private int restartCount;

    /*
     * 最近一次错误消息
     */
    private String lastError;

    /*
     * 判断进程是否仍在运行
     */
    public boolean isRunning() {
        if (pid <= 0) { return false; }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
