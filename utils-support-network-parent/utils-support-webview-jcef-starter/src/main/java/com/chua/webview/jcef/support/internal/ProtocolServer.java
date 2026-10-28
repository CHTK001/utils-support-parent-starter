package com.chua.webview.jcef.support.internal;

/**
 * 协议服务器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ProtocolServer {

    /**
     * 获取 ProtocolType。
     *
     * @return 协议类型
     */
    ProtocolType getProtocolType();

    /**
     * 获取 ServerUrl。
     *
     * @return 服务器 URL
     */
    String getServerUrl();

    /**
     * 启动服务器（如需）。
     */
    void startIfNeeded();

    /**
     * 关闭服务器。
     */
    void shutdown();
}
