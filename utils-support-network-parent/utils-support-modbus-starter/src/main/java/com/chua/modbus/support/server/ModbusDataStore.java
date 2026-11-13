package com.chua.modbus.support.server;

/**
 * Modbus 服务端数据区。
 *
 * <p>把四类数据区暴露给业务侧：线圈、离散输入、保持寄存器、输入寄存器。
 * 服务端只负责协议解析与寻址，寄存器内容由本接口提供，便于接入采集缓存或设备影子。</p>
 *
 * <p>地址均为 0 基址（与 Modbus PDU 一致），业务侧使用 4xxxx / 0xxxx 编号时需自行换算。</p>
 *
 * <p>实现必须支持被多个连接线程并发访问；越界访问应抛出携带
 * {@link com.chua.modbus.support.ModbusException#ILLEGAL_DATA_ADDRESS} 的异常，
 * 服务端会据此回 0x02 异常响应。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ModbusDataStore {

    /**
     * 读保持寄存器。
     *
     * @param address  起始地址
     * @param quantity 数量
     * @return 寄存器值（无符号 16 位）
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    int[] readHoldingRegisters(int address, int quantity);

    /**
     * 写保持寄存器。
     *
     * @param address 起始地址
     * @param values  寄存器值
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    void writeHoldingRegisters(int address, int[] values);

    /**
     * 读输入寄存器。
     *
     * @param address  起始地址
     * @param quantity 数量
     * @return 寄存器值（无符号 16 位）
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    int[] readInputRegisters(int address, int quantity);

    /**
     * 写输入寄存器（通常由采集侧刷新，不响应客户端写请求）。
     *
     * @param address 起始地址
     * @param values  寄存器值
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    void writeInputRegisters(int address, int[] values);

    /**
     * 读线圈。
     *
     * @param address  起始地址
     * @param quantity 数量
     * @return 位值
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    boolean[] readCoils(int address, int quantity);

    /**
     * 写线圈。
     *
     * @param address 起始地址
     * @param values  位值
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    void writeCoils(int address, boolean[] values);

    /**
     * 读离散输入。
     *
     * @param address  起始地址
     * @param quantity 数量
     * @return 位值
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    boolean[] readDiscreteInputs(int address, int quantity);

    /**
     * 写离散输入（通常由采集侧刷新，不响应客户端写请求）。
     *
     * @param address 起始地址
     * @param values  位值
     * @throws com.chua.modbus.support.ModbusException 地址越界时抛出
     */
    void writeDiscreteInputs(int address, boolean[] values);

    /**
     * 获取数据区大小（可寻址单元数量）。
     *
     * @return 数据区大小
     */
    int getSize();
}
