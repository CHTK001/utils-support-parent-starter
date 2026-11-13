package com.chua.modbus.support;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;

/**
 * 测试公共常量与端口探测工具。
 *
 * <p>类名不以 {@code Test} 结尾，避免被 surefire 当作测试类执行。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class ModbusTestSupport {

    /**
     * 测试从站地址
     */
    static final int UNIT_ID = 1;
    /**
     * 测试数据区大小：故意小于 65536，便于验证越界
     */
    static final int DATA_STORE_SIZE = 100;

    private ModbusTestSupport() {
    }

    /**
     * 分配一个空闲 TCP 端口。
     *
     * <p>j2mod 从站不接受端口 0（会回落到 502），因此测试先探测再绑定。</p>
     *
     * @return 空闲端口
     * @throws IOException 分配失败
     */
    static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 分配一个空闲 UDP 端口。
     *
     * @return 空闲端口
     * @throws IOException 分配失败
     */
    static int findFreeUdpPort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
