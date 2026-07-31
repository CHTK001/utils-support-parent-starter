package com.chua.utils.support.appimage;

/**
 * AppImage 运行状态
 *
 * @author CH
 */
public enum AppImageStatus {

    /*
     * 已停止
     */
    STOPPED,

    /*
     * 启动中
     */
    STARTING,

    /*
     * 运行中
     */
    RUNNING,

    /*
     * 停止中
     */
    STOPPING,

    /*
     * 重启中
     */
    RESTARTING,

    /*
     * 错误状态
     */
    ERROR,

    /*
     * 未知状态
     */
    UNKNOWN
}