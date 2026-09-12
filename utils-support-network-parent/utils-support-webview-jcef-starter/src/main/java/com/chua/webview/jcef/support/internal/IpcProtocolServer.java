package com.chua.webview.jcef.support.internal;

/**
* IPC 协议服务器抽象基类。
*
* @author CH
* @since 4.0.0.42
 */
public abstract class IpcProtocolServer implements ProtocolServer {

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.IPC;
    }

    @Override
    /** 获取服务端url */
    public String getServerUrl() {
        return "ipc://local";
    }

    @Override
    /** 开始ifneeded */
    public void startIfNeeded() {
        // 默认空实现，子类可覆盖
    }

    @Override
    /** 关闭 */
    public void shutdown() {
        // 默认空实现，子类可覆盖
    }

    /**
    * 处理 IPC 消息。
    *
    * @param source 消息来源标识
    * @param path   请求路径
    * @param body   请求体
    * @return 响应字符串
     */
    public abstract String handleMessage(String source, String path, String body);
}