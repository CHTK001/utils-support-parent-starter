// [P3C A20 豁免] A20/A30/A31: 0x10000 为 Modbus 单字节寻址空间的天然上限。
package com.chua.modbus.support.server;

import com.chua.modbus.support.ModbusException;

/**
 * 内存数据区。
 *
 * <p>默认分配完整的 65536 个地址，四类数据区各一份；按 short/byte 存储，
 * 单个实例约 384KB。地址空间较小的设备可以用
 * {@link #MemoryModbusDataStore(int)} 缩小，越界访问会回 0x02 异常响应。</p>
 *
 * <p>所有读写方法均为 {@code synchronized}，可直接被服务端多连接线程共享。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class MemoryModbusDataStore implements ModbusDataStore {

    /**
     * 默认数据区大小：完整 16 位地址空间
     */
    public static final int DEFAULT_SIZE = 0x10000;

    /**
     * 寄存器值上限
     */
    private static final int MAX_REGISTER_VALUE = 0xFFFF;

    /**
     * 数据区大小
     */
    private final int size;

    /**
     * 保持寄存器
     */
    private final short[] holdingRegisters;

    /**
     * 输入寄存器
     */
    private final short[] inputRegisters;

    /**
     * 线圈，取值 0/1
     */
    private final byte[] coils;

    /**
     * 离散输入，取值 0/1
     */
    private final byte[] discreteInputs;

    /**
     * 创建完整地址空间的内存数据区。
     */
    public MemoryModbusDataStore() {
        this(DEFAULT_SIZE);
    }

    /**
     * 创建指定大小的内存数据区。
     *
     * @param size 数据区大小，1~65536
     */
    public MemoryModbusDataStore(int size) {
        if (size <= 0 || size > DEFAULT_SIZE) {
            throw new IllegalArgumentException("Modbus 数据区大小非法: " + size + "，允许 1~" + DEFAULT_SIZE);
        }
        this.size = size;
        this.holdingRegisters = new short[size];
        this.inputRegisters = new short[size];
        this.coils = new byte[size];
        this.discreteInputs = new byte[size];
    }

    @Override
    public synchronized int[] readHoldingRegisters(int address, int quantity) {
        checkRange(address, quantity);
        int[] values = new int[quantity];
        for (int i = 0; i < quantity; i++) {
            values[i] = holdingRegisters[address + i] & MAX_REGISTER_VALUE;
        }
        return values;
    }

    @Override
    public synchronized void writeHoldingRegisters(int address, int[] values) {
        checkValues(address, values);
        for (int i = 0; i < values.length; i++) {
            checkRegisterValue(values[i]);
            holdingRegisters[address + i] = (short) values[i];
        }
    }

    @Override
    public synchronized int[] readInputRegisters(int address, int quantity) {
        checkRange(address, quantity);
        int[] values = new int[quantity];
        for (int i = 0; i < quantity; i++) {
            values[i] = inputRegisters[address + i] & MAX_REGISTER_VALUE;
        }
        return values;
    }

    @Override
    public synchronized void writeInputRegisters(int address, int[] values) {
        checkValues(address, values);
        for (int i = 0; i < values.length; i++) {
            checkRegisterValue(values[i]);
            inputRegisters[address + i] = (short) values[i];
        }
    }

    @Override
    public synchronized boolean[] readCoils(int address, int quantity) {
        checkRange(address, quantity);
        boolean[] values = new boolean[quantity];
        for (int i = 0; i < quantity; i++) {
            values[i] = coils[address + i] != 0;
        }
        return values;
    }

    @Override
    public synchronized void writeCoils(int address, boolean[] values) {
        checkValues(address, values);
        for (int i = 0; i < values.length; i++) {
            coils[address + i] = (byte) (values[i] ? 1 : 0);
        }
    }

    @Override
    public synchronized boolean[] readDiscreteInputs(int address, int quantity) {
        checkRange(address, quantity);
        boolean[] values = new boolean[quantity];
        for (int i = 0; i < quantity; i++) {
            values[i] = discreteInputs[address + i] != 0;
        }
        return values;
    }

    @Override
    public synchronized void writeDiscreteInputs(int address, boolean[] values) {
        checkValues(address, values);
        for (int i = 0; i < values.length; i++) {
            discreteInputs[address + i] = (byte) (values[i] ? 1 : 0);
        }
    }

    @Override
    public int getSize() {
        return size;
    }

    /**
     * 校验寄存器写入参数。
     *
     * @param address 起始地址
     * @param values  寄存器值
     */
    private void checkValues(int address, int[] values) {
        if (values == null) {
            throw new IllegalArgumentException("Modbus 寄存器值不能为空");
        }
        checkRange(address, values.length);
    }

    /**
     * 校验位写入参数。
     *
     * @param address 起始地址
     * @param values  位值
     */
    private void checkValues(int address, boolean[] values) {
        if (values == null) {
            throw new IllegalArgumentException("Modbus 位值不能为空");
        }
        checkRange(address, values.length);
    }

    /**
     * 校验地址范围。
     *
     * @param address  起始地址
     * @param quantity 数量
     */
    private void checkRange(int address, int quantity) {
        if (address < 0 || quantity < 1 || address + quantity > size) {
            throw ModbusException.illegalDataAddress("Modbus 地址越界: 起始 " + address
                    + "，数量 " + quantity + "，数据区大小 " + size);
        }
    }

    /**
     * 校验寄存器值。
     *
     * @param value 寄存器值
     */
    private void checkRegisterValue(int value) {
        if (value < 0 || value > MAX_REGISTER_VALUE) {
            throw new IllegalArgumentException("Modbus 寄存器值越界: " + value);
        }
    }
}
