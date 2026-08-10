package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;

import java.io.IOException;

/**
 * 远控协议桥接器抽象。
 *
 * <p>每种协议（VNC / SSH / RDP / RustDesk）实现此接口，
 * 负责浏览器 WS↔被控主机 双向字节转发。</p>
 *
 * <p>实现时：
 *   <ul>
 *     <li>{@link #connect()} 主动连接被控主机（VNC:5900 / SSH:22 / guacd:4822）</li>
 *     <li>{@link #disconnect()} 关闭连接</li>
 *     <li>{@link #writeToRemote(byte[])} / {@link #readFromRemote()} 提供帧透传</li>
 *     <li>不参与 WS endpoint 注册 — 由 {@link com.chua.gateway.server.server.GatewayServerBootstrap}
 *         绑定
 *   </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RemoteBridge {

    /**
     * 返回底层连接信息。
     *
     * @return Connection record
     */
    Connection connection();

    /**
     * 主动连接被控主机。
     *
     * <p>对 VNC/SSH：建立 raw TCP 套接字。
     * 对 RDP（guacd）：建立到 guacd 子进程的 TCP 套接字。</p>
     *
     * @throws Exception 连接失败
     */
    void connect() throws Exception;

    /**
     * 断开已建立的连接。
     */
    void disconnect();

    /**
     * 是否已连接。
     *
     * @return true 表示已建立到被控主机或 guacd 的连接
     */
    boolean isConnected();

    /**
     * 把浏览器侧 WebSocket 帧写入被控主机。
     *
     * @param bytes WebSocket 解码后的二进制帧
     * @throws IOException 写入失败
     */
    void writeToRemote(byte[] bytes) throws IOException;

    /**
     * 从被控主机读取一帧（阻塞直到可读）。
     *
     * @return 读取到的字节
     * @throws IOException 读取失败或连接关闭
     */
    byte[] readFromRemote() throws IOException;
}
