package com.chua.common.support.network.sync.impl;

import com.chua.common.support.network.server.ServerSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TcpSyncServer} 的真实套接字冒烟测试。
 *
 * <p>该实现此前没有任何测试。这里覆盖最小闭环：连接 → 发一行 {@code register:u1}
 * → 收到 {@code registered:u1} 回执，验证读事件分发、行切分、业务回调与
 * 写队列冲刷（{@code flushQueue} 的「交还写权 + 清 OP_WRITE」顺序）在真实链路上可用。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class TcpSyncServerTest {

    /**
     * 取一个当前空闲的端口。
     *
     * @return 空闲端口
     * @throws Exception 套接字操作失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 注册行消息能收到回执。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(30)
    @DisplayName("注册行消息能收到 registered 回执")
    void registerLineGetsAck() throws Exception {
        int port = freePort();
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp")
                .host("127.0.0.1")
                .port(port)
                .auto(false)
                .build();
        TcpSyncServer server = new TcpSyncServer(setting);
        try {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                // [P3C A20 豁免]
                socket.setSoTimeout(10000);
                socket.setTcpNoDelay(true);
                socket.getOutputStream().write("register:u1\n".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("registered:u1", reader.readLine(), "应收到注册回执");
            }
        } finally {
            server.stop();
        }
    }
}
