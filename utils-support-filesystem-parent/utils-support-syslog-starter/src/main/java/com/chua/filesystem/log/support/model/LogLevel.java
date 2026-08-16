package com.chua.filesystem.log.support.model;

/**
 * 系统日志级别枚举
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum LogLevel {

    /** 追踪 */
    TRACE(0),

    /** 调试 */
    DEBUG(1),

    /** 信息 */
    INFO(2),

    /** 警告 */
    WARNING(3),

    /** 错误 */
    ERROR(4),

    /** 严重 */
    CRITICAL(5);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int getSeverity() {
        return severity;
    }

    public boolean meetsMinimum(LogLevel minLevel) {
        return minLevel == null || this.severity >= minLevel.severity;
    }
}
