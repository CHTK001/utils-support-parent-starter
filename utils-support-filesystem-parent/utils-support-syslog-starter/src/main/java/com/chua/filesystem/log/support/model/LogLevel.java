package com.chua.filesystem.log.support.model;

/**
 * 系统日志级别枚举
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum LogLevel {

    /**
     * 追踪
    */
    TRACE(0),

    /**
     * 调试
    */
    DEBUG(1),

    /**
     * 信息
    */
    INFO(2),

    /**
     * 警告
    */
    WARNING(3),

    /**
     * 错误
    */
    ERROR(4),

    /**
     * 严重
     * @param 5 方法入参 5
     */
    CRITICAL(5);

    /**
     * Severity
    */
    private final int severity;

    /**
     * 构造方法，创建 Log级别 实例。
     *
     * @param severity 方法入参 severity
     */
    LogLevel(int severity) {
        this.severity = severity;
    }

    /**
     * 获取Severity
     *
     * @return 获取severity的结果
     */
    public int getSeverity() {
        return severity;
    }

    /**
     * meetsminimum
     *
     * @param minLevel 最小级别
     * @return meetsMinimum的结果
     */
    public boolean meetsMinimum(LogLevel minLevel) {
        return minLevel == null || this.severity >= minLevel.severity;
    }
}
