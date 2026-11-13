package com.chua.modbus.support;

import com.chua.modbus.support.server.MemoryModbusDataStore;
import com.chua.modbus.support.server.ModbusServer;
import com.chua.modbus.support.server.ModbusUdpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static com.chua.modbus.support.ModbusTestSupport.DATA_STORE_SIZE;
import static com.chua.modbus.support.ModbusTestSupport.UNIT_ID;
import static com.chua.modbus.support.ModbusTestSupport.findFreeUdpPort;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modbus UDP 客户端与服务端的真实回环测试。
 *
 * <p>UDP 无连接，j2mod 从站启动时监听线程与 {@code open()} 返回之间存在极短竞争，
 * 因此客户端配置了重试；这样既覆盖丢包重试，也避免测试偶发失败。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class ModbusUdpExchangeTest {

    private ModbusServer server;
    private ModbusClient client;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        port = findFreeUdpPort();
        server = ModbusServer.create(udpSetting(port));
        assertInstanceOf(ModbusUdpServer.class, server);
        server.setDataStore(new MemoryModbusDataStore(DATA_STORE_SIZE));
        server.start();

        client = ModbusClient.create(udpSetting(port).setRetries(2));
        client.connect();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
        // j2mod 的 UDP 监听线程是异步停止的：等端口真正释放，避免下个用例绑到同一端口后数据报被两个监听器分流
        awaitUdpPortReleased(port);
    }

    @Test
    void shouldExchangeRegistersOverUdp() {
        assertTrue(client.isConnected());

        client.writeMultipleRegisters(UNIT_ID, 0, new int[]{5, 6, 7});
        assertArrayEquals(new int[]{5, 6, 7}, client.readHoldingRegisters(UNIT_ID, 0, 3));

        client.writeSingleRegister(UNIT_ID, 9, 0x1234);
        assertArrayEquals(new int[]{0x1234}, client.readHoldingRegisters(9, 1));
    }

    @Test
    void shouldExchangeCoilsOverUdp() {
        // [P3C A20 豁免]
        client.writeMultipleCoils(UNIT_ID, 20, new boolean[]{true, false, true});
        // 先看服务端数据区是否已落盘，区分“写没到”与“读没到”
        // [P3C A20 豁免]
        assertArrayEquals(new boolean[]{true, false, true}, server.getDataStore().readCoils(20, 3));
        // [P3C A20 豁免]
        assertArrayEquals(new boolean[]{true, false, true}, client.readCoils(UNIT_ID, 20, 3));
    }

    @Test
    void shouldStopUdpServerQuickly() {
        long start = System.currentTimeMillis();
        server.stop();
        long elapsed = System.currentTimeMillis() - start;
        // j2mod 原生关闭固定等 5s；服务端唤醒监听线程后应远小于该值
        assertTrue(elapsed < 3_000L, "UDP 服务端关闭耗时过长: " + elapsed + "ms");
    }

    /**
     * 构造 UDP 测试配置。
     *
     * @param port 端口
     * @return 配置
     */
    private static ModbusSetting udpSetting(int port) {
        return ModbusSetting.defaults()
                .setProtocol(ModbusProtocol.UDP)
                .setHost("127.0.0.1")
                .setPort(port)
                .setUnitId(UNIT_ID)
                .setReadTimeout(2_000);
    }

    /**
     * 等待 UDP 端口真正释放。
     *
     * @param port 端口
     * @throws InterruptedException 等待被中断
     */
    private static void awaitUdpPortReleased(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1_000L;
        while (System.currentTimeMillis() < deadline) {
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return;
            } catch (IOException ignored) {
                // [P3C A20 豁免]
                Thread.sleep(50L);
            }
        }
    }
}
