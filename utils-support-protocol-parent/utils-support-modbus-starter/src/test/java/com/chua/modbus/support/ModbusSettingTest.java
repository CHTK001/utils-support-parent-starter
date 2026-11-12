package com.chua.modbus.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modbus 配置的协议选择与校验测试。
 *
 * @author CH
 * @since 4.0.0.50
 */
class ModbusSettingTest {

    @Test
    void shouldDefaultToTcp() {
        ModbusSetting setting = ModbusSetting.defaults();
        assertEquals(ModbusProtocol.TCP, setting.getProtocol());
        assertEquals(ModbusSetting.DEFAULT_HOST, setting.getHost());
        assertEquals(ModbusSetting.DEFAULT_PORT, setting.getPort());
        assertEquals(ModbusSetting.DEFAULT_UNIT_ID, setting.getUnitId());
        assertDoesNotThrow(setting::validate);
    }

    @Test
    void shouldSyncSerialEncodingWithProtocol() {
        ModbusSetting setting = ModbusSetting.ofSerial("COM3");
        assertEquals(ModbusProtocol.RTU, setting.getProtocol());
        assertEquals(ModbusSetting.SERIAL_ENCODING_RTU, setting.getSerialEncoding());

        setting.setProtocol(ModbusProtocol.ASCII);
        assertEquals(ModbusSetting.SERIAL_ENCODING_ASCII, setting.getSerialEncoding());

        // 切回网络协议不重置串口编码，避免丢失用户显式设置
        setting.setProtocol(ModbusProtocol.TCP);
        assertEquals(ModbusSetting.SERIAL_ENCODING_ASCII, setting.getSerialEncoding());
    }

    @Test
    void shouldRejectNullProtocol() {
        assertThrows(IllegalArgumentException.class,
                () -> ModbusSetting.defaults().setProtocol(null));
    }

    @Test
    void shouldValidateNetworkConfig() {
        assertThrows(ModbusException.class, () -> ModbusSetting.defaults().setPort(0).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.defaults().setHost("").validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.defaults().setUnitId(256).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.defaults().setConnectTimeout(0).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.defaults().setReadTimeout(0).validate());

        assertDoesNotThrow(() -> ModbusSetting.of("192.168.1.10", 502)
                .setProtocol(ModbusProtocol.UDP)
                .validate());
    }

    @Test
    void shouldValidateSerialConfig() {
        assertThrows(ModbusException.class, () -> new ModbusSetting().setProtocol(ModbusProtocol.RTU).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.ofSerial("COM3").setBaudRate(0).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.ofSerial("COM3").setDataBits(4).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.ofSerial("COM3").setStopBits(9).validate());
        assertThrows(ModbusException.class, () -> ModbusSetting.ofSerial("COM3").setParity(9).validate());
        assertThrows(ModbusException.class,
                () -> ModbusSetting.ofSerial("COM3").setSerialEncoding("binary").validate());

        assertDoesNotThrow(() -> ModbusSetting.ofSerial("COM3")
                .setBaudRate(19_200)
                .setDataBits(8)
                .setStopBits(ModbusSetting.STOP_BITS_ONE)
                .setParity(ModbusSetting.PARITY_NONE)
                .setProtocol(ModbusProtocol.ASCII)
                .validate());
    }

    @Test
    void shouldParseProtocolName() {
        assertEquals(ModbusProtocol.TCP, ModbusProtocol.fromName("tcp"));
        assertEquals(ModbusProtocol.UDP, ModbusProtocol.fromName("UDP"));
        assertEquals(ModbusProtocol.ASCII, ModbusProtocol.fromName("ascii"));
        assertNull(ModbusProtocol.fromName("rtu-over-tcp"));
        assertNull(ModbusProtocol.fromName(null));

        assertEquals("modbus-udp", ModbusProtocol.UDP.getSpiType());
        assertTrue(ModbusProtocol.RTU.isSerial());
        assertFalse(ModbusProtocol.TCP.isSerial());
    }
}
