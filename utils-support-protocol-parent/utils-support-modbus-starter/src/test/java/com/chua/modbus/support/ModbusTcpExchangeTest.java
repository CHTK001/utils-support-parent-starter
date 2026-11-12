package com.chua.modbus.support;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.modbus.support.client.ModbusRtuClient;
import com.chua.modbus.support.client.ModbusTcpClient;
import com.chua.modbus.support.client.ModbusUdpClient;
import com.chua.modbus.support.server.MemoryModbusDataStore;
import com.chua.modbus.support.server.ModbusRtuServer;
import com.chua.modbus.support.server.ModbusServer;
import com.chua.modbus.support.server.ModbusTcpServer;
import com.chua.modbus.support.server.ModbusUdpServer;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modbus TCP 客户端与服务端的真实回环测试。
 *
 * <p>服务端由 j2mod 从站监听随机空闲端口（TCP/UDP），客户端由 j2mod 主站走完整网络链路，
 * 不使用 mock，验证寄存器/线圈读写、异常码映射、协议选择、SPI 注册与串口参数转换。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class ModbusTcpExchangeTest {

    /**
     * 测试数据区大小：故意小于 65536，便于验证越界
     */
    private static final int DATA_STORE_SIZE = 100;
    /**
     * 从站地址
     */
    private static final int UNIT_ID = 1;

    private ModbusTcpServer server;
    private ModbusClient client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreePort();
        ServerSetting serverSetting = ServerSetting.defaults();
        serverSetting.setHost("127.0.0.1");
        serverSetting.setPort(port);
        server = new ModbusTcpServer(serverSetting);
        server.setDataStore(new MemoryModbusDataStore(DATA_STORE_SIZE));
        server.setUnitId(UNIT_ID);
        server.start();

        ModbusSetting clientSetting = ModbusSetting.of("127.0.0.1", port)
                .setUnitId(UNIT_ID)
                .setReadTimeout(2_000);
        client = new ModbusTcpClient(clientSetting);
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void shouldExchangeRegistersAndCoils() {
        assertTrue(client.isConnected());

        client.writeMultipleRegisters(UNIT_ID, 10, new int[]{11, 22, 33});
        assertArrayEquals(new int[]{11, 22, 33}, client.readHoldingRegisters(UNIT_ID, 10, 3));

        client.writeSingleRegister(UNIT_ID, 20, 0xFFFF);
        assertArrayEquals(new int[]{0xFFFF}, client.readHoldingRegisters(20, 1));

        client.writeSingleCoil(UNIT_ID, 5, true);
        assertTrue(client.readCoils(5, 1)[0]);

        client.writeMultipleCoils(UNIT_ID, 30, new boolean[]{true, false, true, true, false, false, true});
        assertArrayEquals(new boolean[]{true, false, true, true, false, false, true},
                client.readCoils(UNIT_ID, 30, 7));
    }

    @Test
    void shouldReadInputAreasWrittenByServerSide() {
        server.getDataStore().writeInputRegisters(3, new int[]{7, 8, 9});
        assertArrayEquals(new int[]{7, 8, 9}, client.readInputRegisters(UNIT_ID, 3, 3));

        server.getDataStore().writeDiscreteInputs(2, new boolean[]{true, false, true});
        assertArrayEquals(new boolean[]{true, false, true}, client.readDiscreteInputs(UNIT_ID, 2, 3));
    }

    @Test
    void shouldSwitchDataStoreWhileRunning() {
        MemoryModbusDataStore replacement = new MemoryModbusDataStore(DATA_STORE_SIZE);
        replacement.writeHoldingRegisters(0, new int[]{77, 88});
        server.setDataStore(replacement);

        assertArrayEquals(new int[]{77, 88}, client.readHoldingRegisters(UNIT_ID, 0, 2));
    }

    @Test
    void shouldExchangeOverUdp() throws IOException {
        int port = findFreeUdpPort();
        ModbusServer udpServer = ModbusServer.create(ModbusSetting.defaults()
                .setProtocol(ModbusProtocol.UDP)
                .setHost("127.0.0.1")
                .setPort(port)
                .setUnitId(UNIT_ID));
        assertInstanceOf(ModbusUdpServer.class, udpServer);
        udpServer.setDataStore(new MemoryModbusDataStore(DATA_STORE_SIZE));
        udpServer.start();
        try (ModbusClient udpClient = ModbusClient.create(ModbusSetting.defaults()
                .setProtocol(ModbusProtocol.UDP)
                .setHost("127.0.0.1")
                .setPort(port)
                .setUnitId(UNIT_ID)
                .setReadTimeout(2_000)
                .setRetries(2))) {
            udpClient.connect();
            udpClient.writeMultipleRegisters(UNIT_ID, 0, new int[]{5, 6, 7});
            assertArrayEquals(new int[]{5, 6, 7}, udpClient.readHoldingRegisters(UNIT_ID, 0, 3));
        } finally {
            udpServer.stop();
        }
    }

    @Test
    void shouldSelectTransportByConfiguredProtocol() {
        assertInstanceOf(ModbusTcpClient.class,
                ModbusClient.create(ModbusSetting.of("127.0.0.1", 502)));
        assertInstanceOf(ModbusUdpClient.class,
                ModbusClient.create(ModbusSetting.of("127.0.0.1", 502).setProtocol(ModbusProtocol.UDP)));
        assertInstanceOf(ModbusTcpServer.class,
                ModbusServer.create(ModbusSetting.of("127.0.0.1", 502)));
        assertInstanceOf(ModbusUdpServer.class,
                ModbusServer.create(ModbusSetting.of("127.0.0.1", 502).setProtocol(ModbusProtocol.UDP)));

        ModbusSetting rtu = ModbusSetting.ofSerial("COM3");
        assertEquals(ModbusProtocol.RTU, rtu.getProtocol());
        assertEquals(ModbusSetting.SERIAL_ENCODING_RTU, rtu.getSerialEncoding());
        assertInstanceOf(ModbusRtuClient.class, ModbusClient.create(rtu));
        assertInstanceOf(ModbusRtuServer.class, ModbusServer.create(rtu));

        ModbusSetting ascii = ModbusSetting.ofSerial("COM3").setProtocol(ModbusProtocol.ASCII);
        assertEquals(ModbusSetting.SERIAL_ENCODING_ASCII, ascii.getSerialEncoding());
        assertInstanceOf(ModbusRtuClient.class, ModbusClient.create(ascii));
        assertInstanceOf(ModbusRtuServer.class, ModbusServer.create(ascii));
    }

    @Test
    void shouldReturnIllegalDataAddressWhenOutOfRange() {
        ModbusException exception = assertThrows(ModbusException.class,
                () -> client.readHoldingRegisters(UNIT_ID, 90, 20));
        assertEquals(ModbusException.ILLEGAL_DATA_ADDRESS, exception.getExceptionCode());
        assertTrue(exception.isProtocolException());
    }

    @Test
    void shouldResolveClientAndServerBySpi() {
        ModbusClient tcpClient = ServiceProvider.of(ModbusClient.class).getExtension("modbus-tcp");
        assertInstanceOf(ModbusTcpClient.class, tcpClient);
        tcpClient.close();

        ModbusClient rtuClient = ServiceProvider.of(ModbusClient.class).getExtension("modbus-rtu");
        assertInstanceOf(ModbusRtuClient.class, rtuClient);
        rtuClient.close();

        ModbusClient udpClient = ServiceProvider.of(ModbusClient.class).getExtension("modbus-udp");
        assertInstanceOf(ModbusUdpClient.class, udpClient);
        udpClient.close();

        ModbusServer tcpServer = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-tcp", ServerSetting.defaults());
        assertInstanceOf(ModbusTcpServer.class, tcpServer);

        ModbusServer udpServer = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-udp", ServerSetting.defaults());
        assertInstanceOf(ModbusUdpServer.class, udpServer);

        ModbusServer rtuServer = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-rtu", ModbusSetting.ofSerial("COM_TEST"));
        assertInstanceOf(ModbusRtuServer.class, rtuServer);
    }

    @Test
    void shouldBuildNetworkServerByServerBuilder() throws IOException {
        Server tcpBuilt = ServerBuilder.create()
                .type("modbus")
                .host("127.0.0.1")
                .port(findFreePort())
                .build();
        assertInstanceOf(ModbusTcpServer.class, tcpBuilt);
        assertEquals(ProtocolType.MODBUS, tcpBuilt.getProtocolType());
        tcpBuilt.close();

        Server udpBuilt = ServerBuilder.create()
                .type("modbus-udp")
                .host("127.0.0.1")
                .port(findFreeUdpPort())
                .build();
        assertInstanceOf(ModbusUdpServer.class, udpBuilt);
        assertEquals(ProtocolType.MODBUS, udpBuilt.getProtocolType());
        udpBuilt.close();
    }

    @Test
    void shouldConvertSerialParameters() {
        ModbusSetting setting = ModbusSetting.ofSerial("COM3")
                .setBaudRate(19_200)
                .setDataBits(7)
                .setStopBits(ModbusSetting.STOP_BITS_TWO)
                .setParity(ModbusSetting.PARITY_EVEN)
                .setRs485Mode(true);

        SerialParameters parameters = SerialParameterUtils.toSerialParameters(setting);
        assertEquals("COM3", parameters.getPortName());
        assertEquals(19_200, parameters.getBaudRate());
        assertEquals(7, parameters.getDatabits());
        assertEquals(ModbusSetting.STOP_BITS_TWO, parameters.getStopbits());
        assertEquals(ModbusSetting.PARITY_EVEN, parameters.getParity());
        assertEquals(ModbusSetting.SERIAL_ENCODING_RTU, parameters.getEncoding());
        assertTrue(parameters.getRs485Mode());
    }

    @Test
    void shouldRejectRtuClientWithoutPortName() {
        ModbusRtuClient rtuClient = new ModbusRtuClient(ModbusSetting.defaults());
        assertThrows(ModbusException.class, rtuClient::connect);
        rtuClient.close();
    }

    /**
     * 分配一个空闲 TCP 端口。
     *
     * <p>j2mod 从站不接受端口 0（会回落到 502），因此测试先探测再绑定。</p>
     *
     * @return 空闲端口
     * @throws IOException 分配失败
     */
    private static int findFreePort() throws IOException {
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
    private static int findFreeUdpPort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
