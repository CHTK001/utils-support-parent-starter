package com.chua.common.support.network.tcp;

import java.io.Closeable;

/**
 * TCP 客户端抽象，定义基于长度帧（4 字节头 + 消息体）的 TCP 请求-响应交互。
 *
 * <p>实现类内部维护连接池与读写超时，对外只需提供目标地址和请求帧字节，
 * 即可同步获取响应帧字节；具体协议编解码由调用方（如 RPC 层）负责。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface TcpClient extends Closeable {

    /**
     * 同步发送一帧请求并等待响应帧返回。
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param request 请求帧字节（不含长度头）
     * @return 响应帧字节（不含长度头）
     * @throws Exception 连接失败、超时或对端关闭时抛出
     */
    byte[] call(String host, int port, byte[] request) throws Exception;
}