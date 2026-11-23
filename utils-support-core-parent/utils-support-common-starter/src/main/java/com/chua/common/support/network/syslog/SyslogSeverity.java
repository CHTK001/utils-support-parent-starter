package com.chua.common.support.network.syslog;

/**
 * Syslog 严重级别（RFC 5424 Severity，数值 0-7，数值越小越严重）。
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum SyslogSeverity {

    /**
     * 0 - 系统不可用。
     */
    EMERG(0, "系统不可用"),

    /**
     * 1 - 必须立即处理。
     */
    ALERT(1, "必须立即处理"),

    /**
     * 2 - 严重。
     */
    CRIT(2, "严重"),

    /**
     * 3 - 错误。
     */
    ERR(3, "错误"),

    /**
     * 4 - 警告。
     */
    WARNING(4, "警告"),

    /**
     * 5 - 正常但重要。
     */
    NOTICE(5, "正常但重要"),

    /**
     * 6 - 信息。
     */
    INFO(6, "信息"),

    /**
     * 7 - 调试。
     */
    DEBUG(7, "调试");

    /**
     * RFC 5424 中的数值。
     */
    private final int value;

    /**
     * 中文说明。
     */
    private final String description;

    SyslogSeverity(int value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 获取 RFC 5424 数值。
     *
     * @return 0-7
     */
    public int getValue() {
        return value;
    }

    /**
     * 获取中文说明。
     *
     * @return 说明文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * 按数值解析严重级别，越界时回落到 INFO。
     *
     * @param value RFC 5424 数值
     * @return 严重级别，不为 null
     */
    public static SyslogSeverity fromValue(int value) {
        for (SyslogSeverity severity : values()) {
            if (severity.value == value) {
                return severity;
            }
        }
        return INFO;
    }
}
