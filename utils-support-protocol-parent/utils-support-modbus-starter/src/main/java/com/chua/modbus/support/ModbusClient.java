package com.chua.modbus.support;

import com.chua.common.support.spi.ServiceProvider;

/**
 * Modbus 客户端 SPI。
 *
 * <p>接口只描述 Modbus 的读写语义，不绑定传输方式；具体实现通过
 * {@code META-INF/extensions/com.chua.modbus.support.ModbusClient} 以别名注册。</p>
 *
 * <p>使用方式（由配置的 {@link ModbusSetting#getProtocol()} 选择实现）：</p>
 * <pre>{@code
 * ModbusSetting setting = ModbusSetting.of("192.168.1.10", 502)
 *         .setProtocol(ModbusProtocol.UDP)
 *         .setUnitId(1);
 * try (ModbusClient client = ModbusClient.create(setting)) {
 *     client.connect();
 *     int[] values = client.readHoldingRegisters(0, 10);
 *     client.writeSingleRegister(0, 123);
 * }
 * }</pre>
 *
 * <p>线程安全由 j2mod 主站门面保证：同一实例可被多线程共享，
 * 请求在同一个 TCP 连接或串口上串行化。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ModbusClient extends AutoCloseable {

    /**
     * 按配置中的协议创建客户端。
     *
     * <p>{@link ModbusProtocol#TCP}、{@link ModbusProtocol#UDP}、{@link ModbusProtocol#RTU}、
     * {@link ModbusProtocol#ASCII} 分别映射到 {@code modbus-tcp} / {@code modbus-udp} /
     * {@code modbus-rtu} / {@code modbus-ascii}。</p>
     *
     * @param setting 客户端配置
     * @return 客户端实例
     * @throws ModbusException 配置非法或未找到实现时抛出
     */
    static ModbusClient create(ModbusSetting setting) {
        if (setting == null) {
            throw new ModbusException("Modbus 客户端配置不能为空");
        }
        setting.validate();
        ModbusClient client = ServiceProvider.of(ModbusClient.class)
                .getNewExtension(setting.getProtocol().getSpiType(), setting);
        if (client == null) {
            throw new ModbusException("未找到 Modbus 客户端实现: " + setting.getProtocol().getSpiType());
        }
        return client;
    }

    /**
     * 建立连接。
     *
     * @throws ModbusException 连接失败时抛出
     */
    void connect();

    /**
     * 判断连接是否可用。
     *
     * @return 已连接返回 {@code true}
     */
    boolean isConnected();

    /**
     * 获取客户端配置。
     *
     * @return 配置实例
     */
    ModbusSetting getSetting();

    /**
     * 读保持寄存器（功能码 0x03）。
     *
     * @param unitId   从站地址
     * @param address  起始地址
     * @param quantity 寄存器数量，1~125
     * @return 寄存器值（无符号 16 位）
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    int[] readHoldingRegisters(int unitId, int address, int quantity);

    /**
     * 读输入寄存器（功能码 0x04）。
     *
     * @param unitId   从站地址
     * @param address  起始地址
     * @param quantity 寄存器数量，1~125
     * @return 寄存器值（无符号 16 位）
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    int[] readInputRegisters(int unitId, int address, int quantity);

    /**
     * 读线圈（功能码 0x01）。
     *
     * @param unitId   从站地址
     * @param address  起始地址
     * @param quantity 位数量，1~2000
     * @return 位值
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    boolean[] readCoils(int unitId, int address, int quantity);

    /**
     * 读离散输入（功能码 0x02）。
     *
     * @param unitId   从站地址
     * @param address  起始地址
     * @param quantity 位数量，1~2000
     * @return 位值
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    boolean[] readDiscreteInputs(int unitId, int address, int quantity);

    /**
     * 写单个线圈（功能码 0x05）。
     *
     * @param unitId  从站地址
     * @param address 地址
     * @param value   线圈值
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    void writeSingleCoil(int unitId, int address, boolean value);

    /**
     * 写单个寄存器（功能码 0x06）。
     *
     * @param unitId  从站地址
     * @param address 地址
     * @param value   寄存器值，0~65535
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    void writeSingleRegister(int unitId, int address, int value);

    /**
     * 写多个线圈（功能码 0x0F）。
     *
     * @param unitId  从站地址
     * @param address 起始地址
     * @param values  线圈值，1~1968 个
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    void writeMultipleCoils(int unitId, int address, boolean[] values);

    /**
     * 写多个寄存器（功能码 0x10）。
     *
     * @param unitId  从站地址
     * @param address 起始地址
     * @param values  寄存器值，1~123 个
     * @throws ModbusException 通信失败或对端返回异常响应时抛出
     */
    void writeMultipleRegisters(int unitId, int address, int[] values);

    /**
     * 读保持寄存器，从站地址取 {@link #getSetting()} 中的默认值。
     *
     * @param address  起始地址
     * @param quantity 寄存器数量
     * @return 寄存器值
     */
    default int[] readHoldingRegisters(int address, int quantity) {
        return readHoldingRegisters(getSetting().getUnitId(), address, quantity);
    }

    /**
     * 读输入寄存器，从站地址取默认值。
     *
     * @param address  起始地址
     * @param quantity 寄存器数量
     * @return 寄存器值
     */
    default int[] readInputRegisters(int address, int quantity) {
        return readInputRegisters(getSetting().getUnitId(), address, quantity);
    }

    /**
     * 读线圈，从站地址取默认值。
     *
     * @param address  起始地址
     * @param quantity 位数量
     * @return 位值
     */
    default boolean[] readCoils(int address, int quantity) {
        return readCoils(getSetting().getUnitId(), address, quantity);
    }

    /**
     * 读离散输入，从站地址取默认值。
     *
     * @param address  起始地址
     * @param quantity 位数量
     * @return 位值
     */
    default boolean[] readDiscreteInputs(int address, int quantity) {
        return readDiscreteInputs(getSetting().getUnitId(), address, quantity);
    }

    /**
     * 写单个线圈，从站地址取默认值。
     *
     * @param address 地址
     * @param value   线圈值
     */
    default void writeSingleCoil(int address, boolean value) {
        writeSingleCoil(getSetting().getUnitId(), address, value);
    }

    /**
     * 写单个寄存器，从站地址取默认值。
     *
     * @param address 地址
     * @param value   寄存器值
     */
    default void writeSingleRegister(int address, int value) {
        writeSingleRegister(getSetting().getUnitId(), address, value);
    }

    /**
     * 写多个线圈，从站地址取默认值。
     *
     * @param address 起始地址
     * @param values  线圈值
     */
    default void writeMultipleCoils(int address, boolean[] values) {
        writeMultipleCoils(getSetting().getUnitId(), address, values);
    }

    /**
     * 写多个寄存器，从站地址取默认值。
     *
     * @param address 起始地址
     * @param values  寄存器值
     */
    default void writeMultipleRegisters(int address, int[] values) {
        writeMultipleRegisters(getSetting().getUnitId(), address, values);
    }

    /**
     * 关闭连接并释放资源。
     */
    @Override
    void close();
}
