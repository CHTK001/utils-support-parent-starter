// [P3C A20 豁免] A20/A30/A31: 串口默认值与上限来自 Modbus/串口规范。
package com.chua.modbus.support;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.net.AbstractSerialConnection;

/**
 * Modbus 连接配置。
 *
 * <p>同一个配置对象同时描述 TCP、UDP 与串口（RTU/ASCII）三种传输，由
 * {@link #getProtocol()} 选择：</p>
 * <ul>
 *   <li>TCP / UDP：{@link #getHost()}、{@link #getPort()}</li>
 *   <li>RTU / ASCII：{@link #getPortName()}、波特率、数据位、停止位、校验位</li>
 *   <li>公共：从站地址、超时、重试次数</li>
 * </ul>
 *
 * <p>所有 setter 返回 {@code this}，便于链式配置：</p>
 * <pre>{@code
 * ModbusSetting tcp = ModbusSetting.of("192.168.1.10", 502).setUnitId(1);
 * ModbusSetting udp = ModbusSetting.of("192.168.1.10", 502).setProtocol(ModbusProtocol.UDP);
 * ModbusSetting rtu = ModbusSetting.ofSerial("COM3").setBaudRate(19200).setUnitId(2);
 * ModbusSetting ascii = ModbusSetting.ofSerial("COM3").setProtocol(ModbusProtocol.ASCII);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class ModbusSetting {

    /**
     * Modbus TCP 默认端口
     */
    public static final int DEFAULT_PORT = 502;
    /**
     * 默认从站地址
     */
    public static final int DEFAULT_UNIT_ID = 1;
    /**
     * 默认连接超时（毫秒）
     */
    public static final int DEFAULT_CONNECT_TIMEOUT = 5_000;
    /**
     * 默认读超时（毫秒）
     */
    public static final int DEFAULT_READ_TIMEOUT = 3_000;
    /**
     * 默认主机地址
     */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /**
     * 从站地址上限（单字节）
     */
    public static final int MAX_UNIT_ID = 0xFF;
    /**
     * 端口上限
     */
    private static final int MAX_PORT = 0xFFFF;
    /**
     * 默认波特率
     */
    public static final int DEFAULT_BAUD_RATE = 9_600;
    /**
     * 默认数据位
     */
    public static final int DEFAULT_DATA_BITS = 8;
    /**
     * 校验位：无
     */
    public static final int PARITY_NONE = AbstractSerialConnection.NO_PARITY;
    /**
     * 校验位：奇校验
     */
    public static final int PARITY_ODD = AbstractSerialConnection.ODD_PARITY;
    /**
     * 校验位：偶校验
     */
    public static final int PARITY_EVEN = AbstractSerialConnection.EVEN_PARITY;
    /**
     * 校验位：Mark
     */
    public static final int PARITY_MARK = AbstractSerialConnection.MARK_PARITY;
    /**
     * 校验位：Space
     */
    public static final int PARITY_SPACE = AbstractSerialConnection.SPACE_PARITY;
    /**
     * 停止位：1
     */
    public static final int STOP_BITS_ONE = AbstractSerialConnection.ONE_STOP_BIT;
    /**
     * 停止位：1.5
     */
    public static final int STOP_BITS_ONE_POINT_FIVE = AbstractSerialConnection.ONE_POINT_FIVE_STOP_BITS;
    /**
     * 停止位：2
     */
    public static final int STOP_BITS_TWO = AbstractSerialConnection.TWO_STOP_BITS;
    /**
     * 串口编码：RTU（二进制 + CRC16）
     */
    public static final String SERIAL_ENCODING_RTU = Modbus.SERIAL_ENCODING_RTU;
    /**
     * 串口编码：ASCII（LRC）
     */
    public static final String SERIAL_ENCODING_ASCII = Modbus.SERIAL_ENCODING_ASCII;

    /**
     * 主机地址
     */
    private String host = DEFAULT_HOST;
    /**
     * 传输协议
     */
    private ModbusProtocol protocol = ModbusProtocol.TCP;
    /**
     * 端口
     */
    private int port = DEFAULT_PORT;
    /**
     * 从站地址（Unit Identifier）
     */
    private int unitId = DEFAULT_UNIT_ID;
    /**
     * 连接超时（毫秒）
     */
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    /**
     * 读超时（毫秒）
     */
    private int readTimeout = DEFAULT_READ_TIMEOUT;
    /**
     * 请求失败后的重试次数（不含首次请求）
     */
    private int retries;
    /**
     * 串口名称，如 {@code COM3} 或 {@code /dev/ttyUSB0}
     */
    private String portName;
    /**
     * 波特率
     */
    private int baudRate = DEFAULT_BAUD_RATE;
    /**
     * 数据位
     */
    private int dataBits = DEFAULT_DATA_BITS;
    /**
     * 停止位
     */
    private int stopBits = STOP_BITS_ONE;
    /**
     * 校验位
     */
    private int parity = PARITY_NONE;
    /**
     * 串口编码：{@link #SERIAL_ENCODING_RTU} 或 {@link #SERIAL_ENCODING_ASCII}
     */
    private String serialEncoding = SERIAL_ENCODING_RTU;
    /**
     * 是否回显本机发送的数据（RS-485 半双工回环设备才需要）
     */
    private boolean echo;
    /**
     * 是否启用 RS-485 模式
     */
    private boolean rs485Mode;
    /**
     * RS-485 发送使能电平是否高有效
     */
    private boolean rs485TxEnableActiveHigh = true;
    /**
     * RS-485 是否启用终端电阻
     */
    private boolean rs485EnableTermination;
    /**
     * RS-485 发送期间是否保持接收
     */
    private boolean rs485RxDuringTx;
    /**
     * 串口打开延时（毫秒）
     */
    private int openDelay;

    /**
     * 创建一份独立的默认配置。
     *
     * @return 默认配置
     */
    public static ModbusSetting defaults() {
        return new ModbusSetting();
    }

    /**
     * 创建指定 TCP 地址的配置。
     *
     * @param host 主机地址
     * @param port 端口
     * @return 配置实例
     */
    public static ModbusSetting of(String host, int port) {
        return new ModbusSetting().setHost(host).setPort(port);
    }

    /**
     * 创建指定串口的配置。
     *
     * @param portName 串口名称
     * @return 配置实例
     */
    public static ModbusSetting ofSerial(String portName) {
        return new ModbusSetting().setPortName(portName).setProtocol(ModbusProtocol.RTU);
    }

    /**
     * 获取传输协议。
     *
     * @return 传输协议
     */
    public ModbusProtocol getProtocol() {
        return protocol;
    }

    /**
     * 设置传输协议。
     *
     * <p>选择 {@link ModbusProtocol#RTU} / {@link ModbusProtocol#ASCII} 时会同步
     * {@link #setSerialEncoding(String)}，保证串口实现与协议一致。</p>
     *
     * @param protocol 传输协议
     * @return 当前配置
     */
    public ModbusSetting setProtocol(ModbusProtocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("Modbus 协议不能为空");
        }
        this.protocol = protocol;
        if (protocol == ModbusProtocol.RTU) {
            this.serialEncoding = SERIAL_ENCODING_RTU;
        } else if (protocol == ModbusProtocol.ASCII) {
            this.serialEncoding = SERIAL_ENCODING_ASCII;
        }
        return this;
    }

    /**
     * 获取主机地址。
     *
     * @return 主机地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 设置主机地址。
     *
     * @param host 主机地址
     * @return 当前配置
     */
    public ModbusSetting setHost(String host) {
        this.host = host;
        return this;
    }

    /**
     * 获取端口。
     *
     * @return 端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置端口。
     *
     * @param port 端口
     * @return 当前配置
     */
    public ModbusSetting setPort(int port) {
        this.port = port;
        return this;
    }

    /**
     * 获取从站地址。
     *
     * @return 从站地址
     */
    public int getUnitId() {
        return unitId;
    }

    /**
     * 设置从站地址。
     *
     * @param unitId 从站地址，取值 0~255
     * @return 当前配置
     */
    public ModbusSetting setUnitId(int unitId) {
        this.unitId = unitId;
        return this;
    }

    /**
     * 获取连接超时。
     *
     * @return 连接超时（毫秒）
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置连接超时。
     *
     * @param connectTimeout 连接超时（毫秒）
     * @return 当前配置
     */
    public ModbusSetting setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /**
     * 获取读超时。
     *
     * @return 读超时（毫秒）
     */
    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * 设置读超时。
     *
     * @param readTimeout 读超时（毫秒）
     * @return 当前配置
     */
    public ModbusSetting setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    /**
     * 获取重试次数。
     *
     * @return 重试次数
     */
    public int getRetries() {
        return retries;
    }

    /**
     * 设置重试次数。
     *
     * <p>由 j2mod 事务层执行；对端返回的 Modbus 异常响应不重试。</p>
     *
     * @param retries 重试次数，负数按 0 处理
     * @return 当前配置
     */
    public ModbusSetting setRetries(int retries) {
        this.retries = retries;
        return this;
    }

    /**
     * 获取串口名称。
     *
     * @return 串口名称
     */
    public String getPortName() {
        return portName;
    }

    /**
     * 设置串口名称。
     *
     * @param portName 串口名称，如 {@code COM3}、{@code /dev/ttyUSB0}
     * @return 当前配置
     */
    public ModbusSetting setPortName(String portName) {
        this.portName = portName;
        return this;
    }

    /**
     * 获取波特率。
     *
     * @return 波特率
     */
    public int getBaudRate() {
        return baudRate;
    }

    /**
     * 设置波特率。
     *
     * @param baudRate 波特率
     * @return 当前配置
     */
    public ModbusSetting setBaudRate(int baudRate) {
        this.baudRate = baudRate;
        return this;
    }

    /**
     * 获取数据位。
     *
     * @return 数据位
     */
    public int getDataBits() {
        return dataBits;
    }

    /**
     * 设置数据位。
     *
     * @param dataBits 数据位，5~8
     * @return 当前配置
     */
    public ModbusSetting setDataBits(int dataBits) {
        this.dataBits = dataBits;
        return this;
    }

    /**
     * 获取停止位。
     *
     * @return 停止位常量
     */
    public int getStopBits() {
        return stopBits;
    }

    /**
     * 设置停止位。
     *
     * @param stopBits 停止位常量，见 {@link #STOP_BITS_ONE} 等
     * @return 当前配置
     */
    public ModbusSetting setStopBits(int stopBits) {
        this.stopBits = stopBits;
        return this;
    }

    /**
     * 获取校验位。
     *
     * @return 校验位常量
     */
    public int getParity() {
        return parity;
    }

    /**
     * 设置校验位。
     *
     * @param parity 校验位常量，见 {@link #PARITY_NONE} 等
     * @return 当前配置
     */
    public ModbusSetting setParity(int parity) {
        this.parity = parity;
        return this;
    }

    /**
     * 获取串口编码。
     *
     * @return {@link #SERIAL_ENCODING_RTU} 或 {@link #SERIAL_ENCODING_ASCII}
     */
    public String getSerialEncoding() {
        return serialEncoding;
    }

    /**
     * 设置串口编码。
     *
     * @param serialEncoding {@link #SERIAL_ENCODING_RTU} 或 {@link #SERIAL_ENCODING_ASCII}
     * @return 当前配置
     */
    public ModbusSetting setSerialEncoding(String serialEncoding) {
        this.serialEncoding = serialEncoding;
        return this;
    }

    /**
     * 是否回显本机发送的数据。
     *
     * @return 回显返回 {@code true}
     */
    public boolean isEcho() {
        return echo;
    }

    /**
     * 设置是否回显本机发送的数据。
     *
     * @param echo 回显为 {@code true}
     * @return 当前配置
     */
    public ModbusSetting setEcho(boolean echo) {
        this.echo = echo;
        return this;
    }

    /**
     * 是否启用 RS-485 模式。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isRs485Mode() {
        return rs485Mode;
    }

    /**
     * 设置是否启用 RS-485 模式。
     *
     * @param rs485Mode 启用为 {@code true}
     * @return 当前配置
     */
    public ModbusSetting setRs485Mode(boolean rs485Mode) {
        this.rs485Mode = rs485Mode;
        return this;
    }

    /**
     * RS-485 发送使能是否高有效。
     *
     * @return 高有效返回 {@code true}
     */
    public boolean isRs485TxEnableActiveHigh() {
        return rs485TxEnableActiveHigh;
    }

    /**
     * 设置 RS-485 发送使能电平。
     *
     * @param rs485TxEnableActiveHigh 高有效为 {@code true}
     * @return 当前配置
     */
    public ModbusSetting setRs485TxEnableActiveHigh(boolean rs485TxEnableActiveHigh) {
        this.rs485TxEnableActiveHigh = rs485TxEnableActiveHigh;
        return this;
    }

    /**
     * RS-485 是否启用终端电阻。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isRs485EnableTermination() {
        return rs485EnableTermination;
    }

    /**
     * 设置 RS-485 终端电阻。
     *
     * @param rs485EnableTermination 启用为 {@code true}
     * @return 当前配置
     */
    public ModbusSetting setRs485EnableTermination(boolean rs485EnableTermination) {
        this.rs485EnableTermination = rs485EnableTermination;
        return this;
    }

    /**
     * RS-485 发送期间是否保持接收。
     *
     * @return 保持接收返回 {@code true}
     */
    public boolean isRs485RxDuringTx() {
        return rs485RxDuringTx;
    }

    /**
     * 设置 RS-485 发送期间是否保持接收。
     *
     * @param rs485RxDuringTx 保持接收为 {@code true}
     * @return 当前配置
     */
    public ModbusSetting setRs485RxDuringTx(boolean rs485RxDuringTx) {
        this.rs485RxDuringTx = rs485RxDuringTx;
        return this;
    }

    /**
     * 获取串口打开延时。
     *
     * @return 延时（毫秒）
     */
    public int getOpenDelay() {
        return openDelay;
    }

    /**
     * 设置串口打开延时。
     *
     * @param openDelay 延时（毫秒）
     * @return 当前配置
     */
    public ModbusSetting setOpenDelay(int openDelay) {
        this.openDelay = openDelay;
        return this;
    }

    /**
     * 按当前协议校验配置。
     *
     * @throws ModbusException 配置非法时抛出
     */
    public void validate() {
        if (protocol == null) {
            throw new ModbusException("Modbus 协议不能为空");
        }
        if (protocol.isSerial()) {
            validateSerial();
            return;
        }
        validateNetwork();
    }

    /**
     * 校验公共配置。
     *
     * @throws ModbusException 配置非法时抛出
     */
    public void validateCommon() {
        if (unitId < 0 || unitId > MAX_UNIT_ID) {
            throw new ModbusException("Modbus 从站地址非法: " + unitId);
        }
        if (connectTimeout <= 0) {
            throw new ModbusException("Modbus 连接超时非法: " + connectTimeout);
        }
        if (readTimeout <= 0) {
            throw new ModbusException("Modbus 读超时非法: " + readTimeout);
        }
    }

    /**
     * 校验网络（TCP/UDP）配置。
     *
     * @throws ModbusException 配置非法时抛出
     */
    public void validateNetwork() {
        validateCommon();
        if (host == null || host.isEmpty()) {
            throw new ModbusException("Modbus 主机地址不能为空");
        }
        if (port <= 0 || port > MAX_PORT) {
            throw new ModbusException("Modbus 端口非法: " + port);
        }
    }

    /**
     * 校验串口配置。
     *
     * @throws ModbusException 配置非法时抛出
     */
    public void validateSerial() {
        validateCommon();
        if (portName == null || portName.isEmpty()) {
            throw new ModbusException("Modbus 串口名称不能为空");
        }
        if (baudRate <= 0) {
            throw new ModbusException("Modbus 波特率非法: " + baudRate);
        }
        if (dataBits < 5 || dataBits > 8) {
            throw new ModbusException("Modbus 数据位非法: " + dataBits);
        }
        if (stopBits != STOP_BITS_ONE && stopBits != STOP_BITS_ONE_POINT_FIVE && stopBits != STOP_BITS_TWO) {
            throw new ModbusException("Modbus 停止位非法: " + stopBits);
        }
        if (parity != PARITY_NONE && parity != PARITY_ODD && parity != PARITY_EVEN
                && parity != PARITY_MARK && parity != PARITY_SPACE) {
            throw new ModbusException("Modbus 校验位非法: " + parity);
        }
        if (!SERIAL_ENCODING_RTU.equalsIgnoreCase(serialEncoding)
                && !SERIAL_ENCODING_ASCII.equalsIgnoreCase(serialEncoding)) {
            throw new ModbusException("Modbus 串口编码非法: " + serialEncoding);
        }
    }
}
