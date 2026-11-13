package com.chua.modbus.support.server;

import com.ghgande.j2mod.modbus.procimg.DigitalIn;
import com.ghgande.j2mod.modbus.procimg.DigitalOut;
import com.ghgande.j2mod.modbus.procimg.FIFO;
import com.ghgande.j2mod.modbus.procimg.File;
import com.ghgande.j2mod.modbus.procimg.IllegalAddressException;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;

/**
 * 把 {@link ModbusDataStore} 适配为 j2mod 过程映像。
 *
 * <p>j2mod 的从站只在写请求时通过 {@code Register.setValue()} / {@code DigitalOut.set()}
 * 回写过程映像，因此寄存器与线圈返回的是「视图」而不是快照：读立即取数据区，
 * 写立即落数据区，业务侧看到的始终是同一份数据。</p>
 *
 * <p>输入寄存器与离散输入是只读语义，返回快照即可。</p>
 *
 * <p>地址越界统一抛 {@link IllegalAddressException}，j2mod 会据此回 0x02 异常响应。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class DataStoreProcessImage implements ProcessImage {

    /**
     * 位宽
     */
    private static final int BITS_PER_WORD = 8;
    /**
     * 单字节掩码
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * 寄存器字节数
     */
    private static final int REGISTER_BYTES = 2;
    /**
     * 寄存器值上限
     */
    private static final int MAX_REGISTER_VALUE = 0xFFFF;

    /**
     * 数据区
     */
    private volatile ModbusDataStore dataStore;

    /**
     * 创建过程映像。
     *
     * @param dataStore 数据区，为 {@code null} 时使用默认内存数据区
     */
    public DataStoreProcessImage(ModbusDataStore dataStore) {
        this.dataStore = dataStore == null ? new MemoryModbusDataStore() : dataStore;
    }

    /**
     * 切换数据区，运行中调用立即生效。
     *
     * @param dataStore 数据区，为 {@code null} 时保留当前数据区
     */
    public void setDataStore(ModbusDataStore dataStore) {
        if (dataStore != null) {
            this.dataStore = dataStore;
        }
    }

    /**
     * 获取当前数据区。
     *
     * @return 数据区
     */
    public ModbusDataStore getDataStore() {
        return dataStore;
    }

    @Override
    public DigitalOut[] getDigitalOutRange(int ref, int count) {
        checkRange(ref, count);
        DigitalOut[] values = new DigitalOut[count];
        for (int i = 0; i < count; i++) {
            values[i] = new DataStoreDigitalOut(dataStore, ref + i);
        }
        return values;
    }

    @Override
    public DigitalOut getDigitalOut(int ref) {
        checkRange(ref, 1);
        return new DataStoreDigitalOut(dataStore, ref);
    }

    @Override
    public int getDigitalOutCount() {
        return dataStore.getSize();
    }

    @Override
    public DigitalIn[] getDigitalInRange(int ref, int count) {
        checkRange(ref, count);
        boolean[] values = dataStore.readDiscreteInputs(ref, count);
        DigitalIn[] result = new DigitalIn[count];
        for (int i = 0; i < count; i++) {
            result[i] = new SimpleDigitalIn(values[i]);
        }
        return result;
    }

    @Override
    public DigitalIn getDigitalIn(int ref) {
        checkRange(ref, 1);
        return new SimpleDigitalIn(dataStore.readDiscreteInputs(ref, 1)[0]);
    }

    @Override
    public int getDigitalInCount() {
        return dataStore.getSize();
    }

    @Override
    public InputRegister[] getInputRegisterRange(int ref, int count) {
        checkRange(ref, count);
        int[] values = dataStore.readInputRegisters(ref, count);
        InputRegister[] result = new InputRegister[count];
        for (int i = 0; i < count; i++) {
            result[i] = new SimpleInputRegister(values[i]);
        }
        return result;
    }

    @Override
    public InputRegister getInputRegister(int ref) {
        checkRange(ref, 1);
        return new SimpleInputRegister(dataStore.readInputRegisters(ref, 1)[0]);
    }

    @Override
    public int getInputRegisterCount() {
        return dataStore.getSize();
    }

    @Override
    public Register[] getRegisterRange(int ref, int count) {
        checkRange(ref, count);
        Register[] result = new Register[count];
        for (int i = 0; i < count; i++) {
            result[i] = new DataStoreRegister(dataStore, ref + i);
        }
        return result;
    }

    @Override
    public Register getRegister(int ref) {
        checkRange(ref, 1);
        return new DataStoreRegister(dataStore, ref);
    }

    @Override
    public int getRegisterCount() {
        return dataStore.getSize();
    }

    @Override
    public File getFile(int ref) {
        throw new IllegalAddressException("Modbus 文件记录未实现: " + ref);
    }

    @Override
    public File getFileByNumber(int number) {
        throw new IllegalAddressException("Modbus 文件记录未实现: " + number);
    }

    @Override
    public int getFileCount() {
        return 0;
    }

    @Override
    public FIFO getFIFO(int ref) {
        throw new IllegalAddressException("Modbus FIFO 未实现: " + ref);
    }

    @Override
    public FIFO getFIFOByAddress(int address) {
        throw new IllegalAddressException("Modbus FIFO 未实现: " + address);
    }

    @Override
    public int getFIFOCount() {
        return 0;
    }

    /**
     * 校验地址范围。
     *
     * @param ref   起始地址
     * @param count 数量
     */
    private void checkRange(int ref, int count) {
        int size = dataStore.getSize();
        if (ref < 0 || count < 1 || ref + count > size) {
            throw new IllegalAddressException("Modbus 地址越界: 起始 " + ref
                    + "，数量 " + count + "，数据区大小 " + size);
        }
    }

    /**
     * 保持寄存器视图：读写都落数据区。
     *
     * @author CH
     * @since 4.0.0.50
     */
    private static final class DataStoreRegister implements Register {

        /**
         * 数据区
         */
        private final ModbusDataStore dataStore;
        /**
         * 寄存器地址
         */
        private final int address;

        /**
         * 创建寄存器视图。
         *
         * @param dataStore 数据区
         * @param address   寄存器地址
         */
        private DataStoreRegister(ModbusDataStore dataStore, int address) {
            this.dataStore = dataStore;
            this.address = address;
        }

        @Override
        public int getValue() {
            return dataStore.readHoldingRegisters(address, 1)[0];
        }

        @Override
        public int toUnsignedShort() {
            return getValue();
        }

        @Override
        public short toShort() {
            return (short) getValue();
        }

        @Override
        public byte[] toBytes() {
            int value = getValue();
            return new byte[]{(byte) ((value >> BITS_PER_WORD) & BYTE_MASK), (byte) (value & BYTE_MASK)};
        }

        @Override
        public void setValue(int value) {
            dataStore.writeHoldingRegisters(address, new int[]{value});
        }

        @Override
        public void setValue(short value) {
            setValue(value & MAX_REGISTER_VALUE);
        }

        @Override
        public void setValue(byte[] bytes) {
            if (bytes == null || bytes.length < REGISTER_BYTES) {
                throw new IllegalArgumentException("Modbus 寄存器字节数不足: "
                        + (bytes == null ? 0 : bytes.length));
            }
            setValue(((bytes[0] & BYTE_MASK) << BITS_PER_WORD) | (bytes[1] & BYTE_MASK));
        }
    }

    /**
     * 线圈视图：读写都落数据区。
     *
     * @author CH
     * @since 4.0.0.50
     */
    private static final class DataStoreDigitalOut implements DigitalOut {

        /**
         * 数据区
         */
        private final ModbusDataStore dataStore;
        /**
         * 线圈地址
         */
        private final int address;

        /**
         * 创建线圈视图。
         *
         * @param dataStore 数据区
         * @param address   线圈地址
         */
        private DataStoreDigitalOut(ModbusDataStore dataStore, int address) {
            this.dataStore = dataStore;
            this.address = address;
        }

        @Override
        public boolean isSet() {
            return dataStore.readCoils(address, 1)[0];
        }

        @Override
        public void set(boolean value) {
            dataStore.writeCoils(address, new boolean[]{value});
        }
    }
}
