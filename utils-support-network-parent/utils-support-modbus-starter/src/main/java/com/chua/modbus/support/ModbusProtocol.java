package com.chua.modbus.support;

/**
 * Modbus 传输协议。
 *
 * <p>用于在 {@link ModbusSetting} 中选择协议，并由
 * {@link ModbusClient#create(ModbusSetting)} / {@link com.chua.modbus.support.server.ModbusServer#create(ModbusSetting)}
 * 映射到对应的 SPI 实现。</p>
 *
 * <ul>
 *   <li>{@link #TCP}：Modbus TCP（MBAP），默认端口 502</li>
 *   <li>{@link #UDP}：Modbus UDP，报文结构与 TCP 相同但无连接</li>
 *   <li>{@link #RTU}：串口 RTU（二进制 + CRC16）</li>
 *   <li>{@link #ASCII}：串口 ASCII（LRC）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public enum ModbusProtocol {

    /**
     * Modbus TCP
     */
    TCP("modbus-tcp", false),
    /**
     * Modbus UDP
     */
    UDP("modbus-udp", false),
    /**
     * 串口 RTU
     */
    RTU("modbus-rtu", true),
    /**
     * 串口 ASCII
     */
    ASCII("modbus-ascii", true);

    /**
     * 对应的 SPI 扩展名
     */
    private final String spiType;
    /**
     * 是否为串口协议
     */
    private final boolean serial;

    /**
     * 构造协议枚举。
     *
     * @param spiType SPI 扩展名
     * @param serial  是否为串口协议
     */
    ModbusProtocol(String spiType, boolean serial) {
        this.spiType = spiType;
        this.serial = serial;
    }

    /**
     * 获取 SPI 扩展名。
     *
     * @return 扩展名
     */
    public String getSpiType() {
        return spiType;
    }

    /**
     * 是否为串口协议。
     *
     * @return 串口协议返回 {@code true}
     */
    public boolean isSerial() {
        return serial;
    }

    /**
     * 按名称解析协议。
     *
     * @param name 协议名，忽略大小写
     * @return 协议，未匹配时返回 {@code null}
     */
    public static ModbusProtocol fromName(String name) {
        if (name == null) {
            return null;
        }
        for (ModbusProtocol protocol : values()) {
            if (protocol.name().equalsIgnoreCase(name)) {
                return protocol;
            }
        }
        return null;
    }
}
