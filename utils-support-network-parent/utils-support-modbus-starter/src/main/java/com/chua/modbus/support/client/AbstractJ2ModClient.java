// [P3C A20 豁免] A20/A30/A31: 0xFFFF 为 Modbus 寄存器值上限。
package com.chua.modbus.support.client;

import com.chua.modbus.support.ModbusClient;
import com.chua.modbus.support.ModbusException;
import com.chua.modbus.support.ModbusSetting;
import com.ghgande.j2mod.modbus.ModbusSlaveException;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.io.ModbusTransaction;
import com.ghgande.j2mod.modbus.msg.WriteMultipleCoilsRequest;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.BitVector;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 j2mod 主站门面的客户端基类。
 *
 * <p>把 j2mod 的 {@link AbstractModbusMaster} 适配为本模块的 {@link ModbusClient}：
 * 连接生命周期、异常码映射、寄存器与位向量的数组转换都收敛在这里，
 * TCP 与串口实现只需要提供各自的主站实例。</p>
 *
 * <h2>线程安全</h2>
 * <p>j2mod 门面的读写方法是 {@code synchronized}，因此同一实例可被多线程共享；
 * 本类只在建连/断连与断线复位时加锁。</p>
 *
 * <h2>断线处理</h2>
 * <p>传输层异常会导致当前主站被关闭，下次调用自动重连；对端返回的 Modbus 异常响应
 * 只转换为 {@link ModbusException}，不动连接。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public abstract class AbstractJ2ModClient implements ModbusClient {

    /**
     * 寄存器值上限
     */
    private static final int MAX_REGISTER_VALUE = 0xFFFF;
    /**
     * 写多个线圈请求的固定数据长度：地址 2 + 数量 2 + 字节数 1
     */
    private static final int COILS_HEADER_BYTES = 5;

    /**
     * 生命周期锁：保护建连与断线复位
     */
    private final Object lifecycleLock = new Object();

    /**
     * 客户端配置
     */
    private final ModbusSetting setting;

    /**
     * j2mod 主站
     */
    private volatile AbstractModbusMaster master;

    /**
     * 创建客户端。
     *
     * @param setting 客户端配置
     */
    protected AbstractJ2ModClient(ModbusSetting setting) {
        this.setting = setting == null ? ModbusSetting.defaults() : setting;
    }

    /**
     * 创建 j2mod 主站。
     *
     * @return 主站实例
     * @throws Exception 主站构造失败
     */
    protected abstract AbstractModbusMaster createMaster() throws Exception;

    @Override
    public void connect() {
        synchronized (lifecycleLock) {
            if (master != null && master.isConnected()) {
                return;
            }
            disconnectLocked();
            AbstractModbusMaster created = null;
            try {
                created = createMaster();
                created.setTimeout(setting.getReadTimeout());
                created.setRetries(Math.max(0, setting.getRetries()));
                created.connect();
                this.master = created;
                log.debug("Modbus 客户端已连接: {}", describeEndpoint());
            } catch (Exception e) {
                if (created != null) {
                    try {
                        created.disconnect();
                    } catch (Exception ignored) {
                        // 建连失败后的清理失败无需上抛
                    }
                }
                throw new ModbusException("Modbus 连接失败(" + describeEndpoint() + "): " + e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean isConnected() {
        AbstractModbusMaster current = master;
        return current != null && current.isConnected();
    }

    @Override
    public ModbusSetting getSetting() {
        return setting;
    }

    @Override
    public int[] readHoldingRegisters(int unitId, int address, int quantity) {
        Register[] registers = execute("readHoldingRegisters",
                current -> current.readMultipleRegisters(unitId, address, quantity));
        return toIntArray(registers);
    }

    @Override
    public int[] readInputRegisters(int unitId, int address, int quantity) {
        InputRegister[] registers = execute("readInputRegisters",
                current -> current.readInputRegisters(unitId, address, quantity));
        return toIntArray(registers);
    }

    @Override
    public boolean[] readCoils(int unitId, int address, int quantity) {
        BitVector bits = execute("readCoils",
                current -> current.readCoils(unitId, address, quantity));
        return toBooleanArray(bits);
    }

    @Override
    public boolean[] readDiscreteInputs(int unitId, int address, int quantity) {
        BitVector bits = execute("readInputDiscretes",
                current -> current.readInputDiscretes(unitId, address, quantity));
        return toBooleanArray(bits);
    }

    @Override
    public void writeSingleCoil(int unitId, int address, boolean value) {
        executeVoid("writeCoil", current -> current.writeCoil(unitId, address, value));
    }

    @Override
    public void writeSingleRegister(int unitId, int address, int value) {
        Register register = new SimpleRegister(value);
        executeVoid("writeSingleRegister", current -> current.writeSingleRegister(unitId, address, register));
    }

    @Override
    public void writeMultipleCoils(int unitId, int address, boolean[] values) {
        BitVector bits = toBitVector(values);
        executeVoid("writeMultipleCoils", current -> issueWriteMultipleCoils(current, unitId, address, bits));
    }

    @Override
    public void writeMultipleRegisters(int unitId, int address, int[] values) {
        Register[] registers = toRegisters(values);
        executeVoid("writeMultipleRegisters", current -> current.writeMultipleRegisters(unitId, address, registers));
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            disconnectLocked();
        }
    }

    /**
     * 执行一次有返回值的调用。
     *
     * @param operation 操作名，用于异常信息
     * @param call      调用逻辑
     * @param <T>       返回类型
     * @return 调用结果
     */
    protected <T> T execute(String operation, ModbusCall<T> call) {
        AbstractModbusMaster current = acquireMaster();
        try {
            return call.call(current);
        } catch (ModbusSlaveException e) {
            throw new ModbusException(e.getType(),
                    "Modbus 异常响应(" + describeEndpoint() + "): " + e.getMessage(), e);
        } catch (Exception e) {
            resetConnection();
            throw new ModbusException("Modbus " + operation + " 失败(" + describeEndpoint() + "): "
                    + e.getMessage(), e);
        }
    }

    /**
     * 执行一次无返回值的调用。
     *
     * @param operation 操作名，用于异常信息
     * @param call      调用逻辑
     */
    protected void executeVoid(String operation, ModbusVoidCall call) {
        execute(operation, current -> {
            call.call(current);
            return Boolean.TRUE;
        });
    }

    /**
     * 发送写多个线圈请求（功能码 0x0F）。
     *
     * <p>不直接调用 j2mod 门面的 {@code writeMultipleCoils}，原因有两个：</p>
     * <ul>
     *   <li>3.2.1 的 {@code WriteMultipleCoilsRequest.setCoils()} 不刷新 dataLength，
     *       UDP 传输按旧长度发送会截掉最后一个数据字节；</li>
     *   <li>UDP 事务在重试耗尽后只记录日志、不抛异常，void 写入会表现为“成功”。</li>
     * </ul>
     * <p>这里显式修正长度，并在事务结束后校验响应是否真的到达。</p>
     *
     * @param current 主站
     * @param unitId  从站地址
     * @param address 起始地址
     * @param bits    线圈值
     * @throws Exception 发送失败或未收到响应
     */
    private void issueWriteMultipleCoils(AbstractModbusMaster current, int unitId, int address, BitVector bits)
            throws Exception {
        WriteMultipleCoilsRequest request = new WriteMultipleCoilsRequest();
        request.setUnitID(unitId);
        request.setReference(address);
        request.setCoils(bits);
        request.setDataLength(bits.byteSize() + COILS_HEADER_BYTES);

        ModbusTransaction transaction = current.getTransport().createTransaction();
        transaction.setRetries(Math.max(0, getSetting().getRetries()));
        transaction.setRequest(request);
        transaction.execute();
        if (transaction.getResponse() == null) {
            throw new ModbusException("Modbus writeMultipleCoils 未收到响应");
        }
    }

    /**
     * 获取已连接的主站，必要时先建连。
     *
     * @return 主站实例
     */
    private AbstractModbusMaster acquireMaster() {
        synchronized (lifecycleLock) {
            if (master == null || !master.isConnected()) {
                connect();
            }
            return master;
        }
    }

    /**
     * 断线后复位连接：下一次调用会重新建连。
     */
    private void resetConnection() {
        synchronized (lifecycleLock) {
            disconnectLocked();
        }
    }

    /**
     * 断开连接（调用方必须持有 {@link #lifecycleLock}）。
     */
    private void disconnectLocked() {
        AbstractModbusMaster current = master;
        master = null;
        if (current == null) {
            return;
        }
        try {
            current.disconnect();
        } catch (Exception e) {
            log.debug("Modbus 断开连接失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 端点描述，用于日志与异常信息。
     *
     * @return 端点描述
     */
    protected String describeEndpoint() {
        return getClass().getSimpleName();
    }

    /**
     * 寄存器数组转 int 数组。
     *
     * @param registers 寄存器数组
     * @return 寄存器值
     */
    private static int[] toIntArray(Register[] registers) {
        int[] values = new int[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i].toUnsignedShort();
        }
        return values;
    }

    /**
     * 输入寄存器数组转 int 数组。
     *
     * @param registers 输入寄存器数组
     * @return 寄存器值
     */
    private static int[] toIntArray(InputRegister[] registers) {
        int[] values = new int[registers.length];
        for (int i = 0; i < registers.length; i++) {
            values[i] = registers[i].toUnsignedShort();
        }
        return values;
    }

    /**
     * 位向量转 boolean 数组。
     *
     * @param bits 位向量
     * @return 位值
     */
    private static boolean[] toBooleanArray(BitVector bits) {
        boolean[] values = new boolean[bits.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = bits.getBit(i);
        }
        return values;
    }

    /**
     * int 数组转寄存器数组。
     *
     * @param values 寄存器值
     * @return 寄存器数组
     */
    private static Register[] toRegisters(int[] values) {
        if (values == null || values.length == 0) {
            throw new ModbusException("Modbus 寄存器值不能为空");
        }
        Register[] registers = new Register[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0 || values[i] > MAX_REGISTER_VALUE) {
                throw new ModbusException("Modbus 寄存器值越界: " + values[i]);
            }
            registers[i] = new SimpleRegister(values[i]);
        }
        return registers;
    }

    /**
     * boolean 数组转位向量。
     *
     * @param values 位值
     * @return 位向量
     */
    private static BitVector toBitVector(boolean[] values) {
        if (values == null || values.length == 0) {
            throw new ModbusException("Modbus 线圈值不能为空");
        }
        BitVector bits = new BitVector(values.length);
        for (int i = 0; i < values.length; i++) {
            bits.setBit(i, values[i]);
        }
        return bits;
    }

    /**
     * j2mod 主站调用。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    protected interface ModbusCall<T> {

        /**
         * 执行调用。
         *
         * @param master 主站
         * @return 调用结果
         * @throws Exception 调用失败
         */
        T call(AbstractModbusMaster master) throws Exception;
    }

    /**
     * j2mod 主站无返回值调用。
     */
    @FunctionalInterface
    protected interface ModbusVoidCall {

        /**
         * 执行调用。
         *
         * @param master 主站
         * @throws Exception 调用失败
         */
        void call(AbstractModbusMaster master) throws Exception;
    }
}
