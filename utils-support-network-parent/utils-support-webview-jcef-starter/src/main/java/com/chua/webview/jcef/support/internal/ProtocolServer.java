package com.chua.webview.jcef.support.internal;

/**
 * 协议服务器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ProtocolServer {

    /**
     * @return 协议类型
     */
    ProtocolType getProtocolType();

    /**
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