package com.chua.modbus.support;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.modbus.support.client.ModbusRtuClient;
import com.chua.modbus.support.client.ModbusTcpClient;
import com.chua.modbus.support.client.ModbusUdpClient;
import com.chua.modbus.support.server.ModbusRtuServer;
import com.chua.modbus.support.server.ModbusServer;
import com.chua.modbus.support.server.ModbusTcpServer;
import com.chua.modbus.support.server.ModbusUdpServer;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.chua.modbus.support.ModbusTestSupport.findFreeTcpPort;
import static com.chua.modbus.support.ModbusTestSupport.findFreeUdpPort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modbus SPI 注册、协议选择与串口参数转换测试。
 *
 * <p>不启动网络或串口：只验证 SPI 解析、配置到实现的映射与参数转换，
 * 真实链路分别在 TCP/UDP 回环测试中验证。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class ModbusSpiTest {

    @Test
    void shouldResolveAllClientAliases() {
        assertInstanceOf(ModbusTcpClient.class, resolveClient("modbus-tcp"));
        assertInstanceOf(ModbusTcpClient.class, resolveClient("modbus"));
        assertInstanceOf(ModbusUdpClient.class, resolveClient("modbus-udp"));
        assertInstanceOf(ModbusUdpClient.class, resolveClient("udp"));
        assertInstanceOf(ModbusRtuClient.class, resolveClient("modbus-rtu"));
        assertInstanceOf(ModbusRtuClient.class, resolveClient("modbus-ascii"));
    }

    @Test
    void shouldResolveAllServerAliases() {
        ModbusServer tcp = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-tcp", ServerSetting.defaults());
        assertInstanceOf(ModbusTcpServer.class, tcp);

        ModbusServer udp = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-udp", ServerSetting.defaults());
        assertInstanceOf(ModbusUdpServer.class, udp);

        ModbusServer rtu = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-rtu", ModbusSetting.ofSerial("COM_TEST"));
        assertInstanceOf(ModbusRtuServer.class, rtu);

        ModbusServer ascii = ServiceProvider.of(ModbusServer.class)
                .getNewExtension("modbus-ascii", ModbusSetting.ofSerial("COM_TEST"));
        assertInstanceOf(ModbusRtuServer.class, ascii);
    }

    @Test
    void shouldSelectTransportByConfiguredProtocol() {
        assertInstanceOf(ModbusTcpClient.class,
                ModbusClient.create(ModbusSetting.of("127.0.0.1", 502)));
        assertInstanceOf(ModbusTcpServer.class,
                ModbusServer.create(ModbusSetting.of("127.0.0.1", 502)));

        assertInstanceOf(ModbusUdpClient.class,
                ModbusClient.create(ModbusSetting.of("127.0.0.1", 502).setProtocol(ModbusProtocol.UDP)));
        assertInstanceOf(ModbusUdpServer.class,
                ModbusServer.create(ModbusSetting.of("127.0.0.1", 502).setProtocol(ModbusProtocol.UDP)));

        ModbusSetting rtu = ModbusSetting.ofSerial("COM3");
        assertEquals(ModbusProtocol.RTU, rtu.getProtocol());
        assertInstanceOf(ModbusRtuClient.class, ModbusClient.create(rtu));
        assertInstanceOf(ModbusRtuServer.class, ModbusServer.create(rtu));

        ModbusSetting ascii = ModbusSetting.ofSerial("COM3").setProtocol(ModbusProtocol.ASCII);
        assertInstanceOf(ModbusRtuClient.class, ModbusClient.create(ascii));
        assertInstanceOf(ModbusRtuServer.class, ModbusServer.create(ascii));
    }

    @Test
    void shouldBuildNetworkServerByServerBuilder() throws IOException {
        Server tcpBuilt = ServerBuilder.create()
                .type("modbus")
                .host("127.0.0.1")
                .port(findFreeTcpPort())
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
    void shouldRejectRtuClientWithoutSerialPort() {
        ModbusRtuClient rtuClient = new ModbusRtuClient(ModbusSetting.defaults());
        assertThrows(ModbusException.class, rtuClient::connect);
        rtuClient.close();
    }

    /**
     * 解析客户端实现并关闭。
     *
     * @param alias SPI 别名
     * @return 客户端实例
     */
    private static ModbusClient resolveClient(String alias) {
        ModbusClient client = ServiceProvider.of(ModbusClient.class).getExtension(alias);
        client.close();
        return client;
    }
}
