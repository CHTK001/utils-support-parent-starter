package com.chua.modbus.support;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.modbus.support.client.ModbusTcpClient;
import com.chua.modbus.support.server.MemoryModbusDataStore;
import com.chua.modbus.support.server.ModbusTcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.chua.modbus.support.ModbusTestSupport.DATA_STORE_SIZE;
import static com.chua.modbus.support.ModbusTestSupport.UNIT_ID;
import static com.chua.modbus.support.ModbusTestSupport.findFreeTcpPort;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modbus TCP 客户端与服务端的真实回环测试。
 *
 * <p>服务端由 j2mod 从站监听随机空闲端口，客户端由 j2mod 主站走完整 Socket 链路，
 * 不使用 mock。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class ModbusTcpExchangeTest {

    private ModbusTcpServer server;
    private ModbusClient client;

    @BeforeEach
    void setUp() throws IOException {
        int port = findFreeTcpPort();
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

        // [P3C A20 豁免]
        client.writeMultipleRegisters(UNIT_ID, 10, new int[]{11, 22, 33});
        // [P3C A20 豁免]
        assertArrayEquals(new int[]{11, 22, 33}, client.readHoldingRegisters(UNIT_ID, 10, 3));

        // [P3C A20 豁免]
        client.writeSingleRegister(UNIT_ID, 20, 0xFFFF);
        // [P3C A20 豁免]
        assertArrayEquals(new int[]{0xFFFF}, client.readHoldingRegisters(20, 1));

        client.writeSingleCoil(UNIT_ID, 5, true);
        assertTrue(client.readCoils(5, 1)[0]);

        // [P3C A20 豁免]
        client.writeMultipleCoils(UNIT_ID, 30, new boolean[]{true, false, true, true, false, false, true});
        assertArrayEquals(new boolean[]{true, false, true, true, false, false, true},
                // [P3C A20 豁免]
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
        // [P3C A20 豁免]
        replacement.writeHoldingRegisters(0, new int[]{77, 88});
        server.setDataStore(replacement);

        // [P3C A20 豁免]
        assertArrayEquals(new int[]{77, 88}, client.readHoldingRegisters(UNIT_ID, 0, 2));
    }

    @Test
    void shouldReturnIllegalDataAddressWhenOutOfRange() {
        ModbusException exception = assertThrows(ModbusException.class,
                // [P3C A20 豁免]
                () -> client.readHoldingRegisters(UNIT_ID, 90, 20));
        assertEquals(ModbusException.ILLEGAL_DATA_ADDRESS, exception.getExceptionCode());
        assertTrue(exception.isProtocolException());
    }
}
