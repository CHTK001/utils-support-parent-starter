package com.chua.datasource.support.middleware;

/**
 * 连接测试结果。
 *
 * <p>约定：实现方<b>失败也返回对象</b>而不是抛异常 —— 连接失败是业务结果，
 * 抛异常会被宿主当成系统错误，前端就没法把「连不上」和「系统坏了」区分开。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareCheckResult {

    /** 是否连通 */
    private final boolean connected;

    /** 服务端版本号，探测不到时为空串 */
    private final String version;

    /** 附加信息（集群名、Broker 数、延迟等），展示用 */
    private final String detail;

    /** 失败原因，成功时为空串 */
    private final String message;

    /**
     * 全字段构造。
     *
     * @param connected 是否连通
     * @param version   服务端版本号
     * @param detail    附加信息
     * @param message   失败原因
     */
    public MiddlewareCheckResult(boolean connected, String version, String detail, String message) {
        this.connected = connected;
        this.version = version;
        this.detail = detail;
        this.message = message;
    }

    /**
     * 成功。
     *
     * @param version 版本号
     * @param detail  附加信息
     * @return 结果对象
     */
    public static MiddlewareCheckResult ok(String version, String detail) {
        return new MiddlewareCheckResult(true, version, detail, "");
    }

    /**
     * 失败。
     *
     * @param message 失败原因
     * @return 结果对象
     */
    public static MiddlewareCheckResult fail(String message) {
        return new MiddlewareCheckResult(false, "", "", message == null ? "未知原因" : message);
    }

    /**
     * 判断是否连接成功。
     *
     * @return 是否连通
     */
    public boolean connected() {
        return connected;
    }

    /**
     * 获取版本号，空值返回空串。
     *
     * @return 服务端版本号，未探测到为空串
     */
    public String version() {
        return version == null ? "" : version;
    }

    /**
     * 获取详细信息，空值返回空串。
     *
     * @return 附加信息
     */
    public String detail() {
        return detail == null ? "" : detail;
    }

    /**
     * 获取消息内容。
     *
     * @return 失败原因，成功时为空串
     */
    public String message() {
        return message == null ? "" : message;
    }

    @Override
    public String toString() {
        return "MiddlewareCheckResult{connected=" + connected + ", version=" + version()
                + ", message=" + message() + '}';
    }

    // ==== Jackson getters (generated) ====
    // 本类用 fluent 访问器（xxx()）对外，Jackson 默认只认 getXxx()/isXxx()，
    // 因此这里补一层 getter 别名；既有调用方不受影响。

    /**
     * Jackson getter，对应 {@link #connected()}。
     *
     * @return connected
     */
    public boolean getConnected() {
        return connected();
    }

    /**
     * Jackson getter，对应 {@link #version()}。
     *
     * @return version
     */
    public String getVersion() {
        return version();
    }

    /**
     * Jackson getter，对应 {@link #detail()}。
     *
     * @return detail
     */
    public String getDetail() {
        return detail();
    }

    /**
     * Jackson getter，对应 {@link #message()}。
     *
     * @return message
     */
    public String getMessage() {
        return message();
    }
}
