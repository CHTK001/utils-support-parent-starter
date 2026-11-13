package com.chua.modbus.support;

import com.chua.modbus.support.server.DataStoreProcessImage;
import com.chua.modbus.support.server.MemoryModbusDataStore;
import com.ghgande.j2mod.modbus.procimg.DigitalOut;
import com.ghgande.j2mod.modbus.procimg.IllegalAddressException;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.chua.modbus.support.ModbusTestSupport.DATA_STORE_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存数据区与 j2mod 过程映像适配测试。
 *
 * <p>重点验证 j2mod 的写路径：FC6/16 会调用 {@code Register.setValue()}，
 * FC5/15 会调用 {@code DigitalOut.set()}，必须真正落回数据区。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class ModbusDataStoreTest {

    private MemoryModbusDataStore dataStore;
    private DataStoreProcessImage processImage;

    @BeforeEach
    void setUp() {
        dataStore = new MemoryModbusDataStore(DATA_STORE_SIZE);
        processImage = new DataStoreProcessImage(dataStore);
    }

    @Test
    void shouldRoundTripAllDataAreas() {
        dataStore.writeHoldingRegisters(0, new int[]{1, 2, 3});
        assertArrayEquals(new int[]{1, 2, 3}, dataStore.readHoldingRegisters(0, 3));

        // [P3C A20 豁免]
        dataStore.writeInputRegisters(10, new int[]{4, 5});
        // [P3C A20 豁免]
        assertArrayEquals(new int[]{4, 5}, dataStore.readInputRegisters(10, 2));

        // [P3C A20 豁免]
        dataStore.writeCoils(20, new boolean[]{true, false, true});
        // [P3C A20 豁免]
        assertArrayEquals(new boolean[]{true, false, true}, dataStore.readCoils(20, 3));

        // [P3C A20 豁免]
        dataStore.writeDiscreteInputs(30, new boolean[]{false, true});
        // [P3C A20 豁免]
        assertArrayEquals(new boolean[]{false, true}, dataStore.readDiscreteInputs(30, 2));

        assertEquals(DATA_STORE_SIZE, dataStore.getSize());
        assertEquals(DATA_STORE_SIZE, processImage.getRegisterCount());
        assertEquals(DATA_STORE_SIZE, processImage.getDigitalOutCount());
    }

    @Test
    void shouldRejectOutOfRangeAccess() {
        ModbusException readException = assertThrows(ModbusException.class,
                // [P3C A20 豁免]
                () -> dataStore.readHoldingRegisters(90, 20));
        assertEquals(ModbusException.ILLEGAL_DATA_ADDRESS, readException.getExceptionCode());

        // [P3C A20 豁免]
        assertThrows(ModbusException.class, () -> dataStore.writeCoils(99, new boolean[]{true, false}));
        assertThrows(ModbusException.class, () -> dataStore.readInputRegisters(-1, 1));
        assertThrows(ModbusException.class, () -> dataStore.readCoils(0, 0));
    }

    @Test
    void shouldRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> dataStore.writeHoldingRegisters(0, null));
        assertThrows(IllegalArgumentException.class, () -> dataStore.writeCoils(0, null));
        assertThrows(IllegalArgumentException.class, () -> dataStore.writeHoldingRegisters(0, new int[]{-1}));
        assertThrows(IllegalArgumentException.class, () -> dataStore.writeHoldingRegisters(0, new int[]{0x10000}));
        assertThrows(IllegalArgumentException.class, () -> new MemoryModbusDataStore(0));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryModbusDataStore(MemoryModbusDataStore.DEFAULT_SIZE + 1));
    }

    @Test
    void shouldWriteRegistersThroughProcessImage() {
        Register register = processImage.getRegister(5);
        // [P3C A20 豁免]
        register.setValue(1234);
        // [P3C A20 豁免]
        assertArrayEquals(new int[]{1234}, dataStore.readHoldingRegisters(5, 1));
        // [P3C A20 豁免]
        assertEquals(1234, register.toUnsignedShort());

        register.setValue(new byte[]{0x12, 0x34});
        assertEquals(0x1234, register.getValue());

        // [P3C A20 豁免]
        Register[] range = processImage.getRegisterRange(10, 2);
        range[0].setValue(7);
        range[1].setValue(8);
        // [P3C A20 豁免]
        assertArrayEquals(new int[]{7, 8}, dataStore.readHoldingRegisters(10, 2));
    }

    @Test
    void shouldWriteCoilsThroughProcessImage() {
        DigitalOut digitalOut = processImage.getDigitalOut(7);
        digitalOut.set(true);
        assertTrue(digitalOut.isSet());
        assertArrayEquals(new boolean[]{true}, dataStore.readCoils(7, 1));

        // [P3C A20 豁免]
        DigitalOut[] range = processImage.getDigitalOutRange(10, 2);
        range[0].set(true);
        range[1].set(false);
        // [P3C A20 豁免]
        assertArrayEquals(new boolean[]{true, false}, dataStore.readCoils(10, 2));
    }

    @Test
    void shouldReadInputAreasAsSnapshot() {
        // [P3C A20 豁免]
        dataStore.writeInputRegisters(2, new int[]{11, 12});
        InputRegister[] registers = processImage.getInputRegisterRange(2, 2);
        // [P3C A20 豁免]
        assertEquals(11, registers[0].toUnsignedShort());
        // [P3C A20 豁免]
        assertEquals(12, registers[1].toUnsignedShort());

        // [P3C A20 豁免]
        dataStore.writeInputRegisters(2, new int[]{99, 98});
        // [P3C A20 豁免]
        assertEquals(11, registers[0].toUnsignedShort());
    }

    @Test
    void shouldRejectOutOfRangeProcessImageAccess() {
        assertThrows(IllegalAddressException.class, () -> processImage.getRegister(DATA_STORE_SIZE));
        // [P3C A20 豁免]
        assertThrows(IllegalAddressException.class, () -> processImage.getRegisterRange(90, 20));
        assertThrows(IllegalAddressException.class, () -> processImage.getDigitalOut(-1));
        assertThrows(IllegalAddressException.class, () -> processImage.getInputRegisterRange(0, DATA_STORE_SIZE + 1));
    }
}
