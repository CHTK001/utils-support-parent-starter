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

    public AppImageManagementResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public AppImageManagementResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public AppImageManagementResponse(boolean success, String message, AppImageStatus status) {
        this.success = success;
        this.message = message;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
    }

    public AppImageManagementResponse(boolean success, String message, AppImageStatus status, long pid) {
        this.success = success;
        this.message = message;
        this.status = status;
        this.pid = pid;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public AppImageStatus getStatus() {
        return status;
    }

    public void setStatus(AppImageStatus status) {
        this.status = status;
    }

    public long getPid() {
        return pid;
    }

    public void setPid(long pid) {
        this.pid = pid;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
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