package com.chua.common.support.network.tcp;

import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;

/**
* TCP 服务端抽象，定义基于长度帧（4 字节头 + 消息体）的 TCP 服务生命周期与帧处理入口。
*
* <p>与具体业务协议解耦：实现类只负责监听端口、接收连接、读取并拼装完整帧，
* 将每一帧完整消息交给 {@link TcpServerHandler} 处理，并负责把响应帧写回对端。</p>
*
* <p>调用顺序约定：先调用 {@link #setHandler} 注册帧处理器，再调用 {@link #start}
* 启动监听，业务方可通过 {@link #getPort()} 获取实际监听端口。</p>
*
* <p>{@code TcpServer} 是 {@link Server} 的标记子接口：实现类经由
* {@link com.chua.common.support.network.server.AbstractServer} 获得
* start/stop/isRunning/getProtocolType 等生命周期能力，本接口仅补充
* 帧处理器注册（{@link #setHandler}）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface TcpServer extends Server {

    /**
    * 注册帧处理器：服务端每收到一帧完整请求即回调一次，处理器返回响应帧字节。
    *
    * @param handler 帧处理器
    * @return 当前实例自身（支持链式调用）
     */
    TcpServer setHandler(TcpServerHandler handler);

    /**
    * 启动 TCP 服务，开始监听端口并接收连接。
     */
    void start();

    /**
    * 获取实际监听端口（端口为 0 时由系统分配，启动后可查询实际值）。
    *
    * @return 监听端口号
     */
    int getPort();
}