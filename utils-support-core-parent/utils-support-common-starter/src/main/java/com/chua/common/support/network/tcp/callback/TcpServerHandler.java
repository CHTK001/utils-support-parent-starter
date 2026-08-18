package com.chua.common.support.network.tcp.callback;

/**
 * TCP 服务端帧处理器：服务端每收到一帧完整请求即回调一次，返回响应帧字节。
 *
 * @author CH
 * @since 4.0.0.42
 */
@FunctionalInterface
public interface TcpServerHandler {

    /**
     * 处理一帧请求消息，返回响应帧字节。
     *
     * @param request 请求帧字节（不含长度头）
     * @return 响应帧字节（不含长度头），为 {@code null} 时不回写响应
     * @throws Exception 处理失败时抛出，服务端将关闭该连接
     */
    byte[] handle(byte[] request) throws Exception;
}