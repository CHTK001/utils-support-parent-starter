package com.chua.common.support.network.syslog;

/**
 * Syslog 设施（RFC 5424 Facility，数值 0-23）。
 *
 * <p>PRI = facility * 8 + severity，facility 表示消息来源类别。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum SyslogFacility {

    /**
     * 0 - 内核消息。
     */
    KERNEL(0, "内核消息"),

    /**
     * 1 - 用户级消息（默认）。
     */
    USER(1, "用户级消息"),

    /**
     * 2 - 邮件系统。
     */
    MAIL(2, "邮件系统"),

    /**
     * 3 - 系统守护进程。
     */
    DAEMON(3, "系统守护进程"),

    /**
     * 4 - 安全/认证。
     */
    AUTH(4, "安全/认证"),

    /**
     * 5 - syslogd 内部。
     */
    SYSLOG(5, "syslogd 内部"),

    /**
     * 6 - 行打印机。
     */
    LPD(6, "行打印机"),

    /**
     * 7 - 网络新闻。
     */
    NEWS(7, "网络新闻"),

    /**
     * 8 - UUCP。
     */
    UUCP(8, "UUCP"),

    /**
     * 9 - 时钟守护进程。
     */
    CRON(9, "时钟守护进程"),

    /**
     * 10 - 安全/认证（私有）。
     */
    AUTHPRIV(10, "安全/认证（私有）"),

    /**
     * 11 - FTP 守护进程。
     */
    FTP(11, "FTP 守护进程"),

    /**
     * 16 - 本地使用 0。
     */
    LOCAL0(16, "本地使用 0"),

    /**
     * 17 - 本地使用 1。
     */
    LOCAL1(17, "本地使用 1"),

    /**
     * 18 - 本地使用 2。
     */
    LOCAL2(18, "本地使用 2"),

    /**
     * 19 - 本地使用 3。
     */
    LOCAL3(19, "本地使用 3"),

    /**
     * 20 - 本地使用 4。
     */
    LOCAL4(20, "本地使用 4"),

    /**
     * 21 - 本地使用 5。
     */
    LOCAL5(21, "本地使用 5"),

    /**
     * 22 - 本地使用 6。
     */
    LOCAL6(22, "本地使用 6"),

    /**
     * 23 - 本地使用 7。
     */
    LOCAL7(23, "本地使用 7");

    /**
     * RFC 5424 中的数值。
     */
    private final int value;

    /**
     * 中文说明。
     */
    private final String description;

    SyslogFacility(int value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 获取 RFC 5424 数值。
     *
     * @return 0-23
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
     * 按数值解析设施，越界时回落到 USER。
     *
     * @param value RFC 5424 数值
     * @return 设施，不为 null
     */
    public static SyslogFacility fromValue(int value) {
        for (SyslogFacility facility : values()) {
            if (facility.value == value) {
                return facility;
            }
        }
        return USER;
    }
}
