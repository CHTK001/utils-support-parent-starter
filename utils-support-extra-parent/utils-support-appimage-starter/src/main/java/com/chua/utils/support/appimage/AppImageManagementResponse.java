   package com.chua.utils.support.appimage;

/**
 * AppImage 管理操作响应结果
 *
 * @author CH
 */
public class AppImageManagementResponse {

    /*
     * 操作是否成功
     */
    private boolean success;

    /*
     * 响应消息
     */
    private String message;

    /*
     * 当前状态
     */
    private AppImageStatus status;

    /*
     * 进程 PID
     */
    private long pid;

    /*
     * 响应时间戳
     */
    private long timestamp;

    /** 创建 AppImageManagementResponse 实例 */
    public AppImageManagementResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 创建 AppImageManagementResponse 实例
     * @param success success
     * @param String String
     */
    public AppImageManagementResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 创建 AppImageManagementResponse 实例
     * @param success success
     * @param String String
     * @param AppImageStatus AppImageStatus
     */
    public AppImageManagementResponse(boolean success, String message, AppImageStatus status) {
        this.success = success;
        this.message = message;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 创建 AppImageManagementResponse 实例
     * @param success success
     * @param String String
     * @param AppImageStatus AppImageStatus
     * @param long long
     */
    public AppImageManagementResponse(boolean success, String message, AppImageStatus status, long pid) {
        this.success = success;
        this.message = message;
        this.status = status;
        this.pid = pid;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters

    /** 是否Success */
    public boolean isSuccess() {
        return success;
    }

    /** 设置Success */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /** 获取Message */
    public String getMessage() {
        return message;
    }

    /** 设置Message */
    public void setMessage(String message) {
        this.message = message;
    }

    /** 获取Status */
    public AppImageStatus getStatus() {
        return status;
    }

    /** 设置Status */
    public void setStatus(AppImageStatus status) {
        this.status = status;
    }

    /** 获取Pid */
    public long getPid() {
        return pid;
    }

    /** 设置Pid */
    public void setPid(long pid) {
        this.pid = pid;
    }

    /** 获取Timestamp */
    public long getTimestamp() {
        return timestamp;
    }

    /** 设置Timestamp */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    /** ToString */
    public String toString() {
        return "AppImageManagementResponse{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", status=" + status +
                ", pid=" + pid +
                ", timestamp=" + timestamp +
                '}';
    }
}