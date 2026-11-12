package com.chua.modbus.support.server;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.modbus.support.ModbusSetting;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import java.net.InetAddress;

/**
 * Modbus TCP 服务端（j2mod TCP 从站实现）。
 *
 * <p>继承 {@link AbstractModbusSlaveServer}，既可以通过 common-starter 的 {@code Server} SPI
 * （{@code type("modbus")}）创建，也可以通过本模块的 {@code ModbusServer} SPI 创建：</p>
 *
 * <pre>{@code
 * // ServerBuilder 入口
 * Server server = ServerBuilder.create().type("modbus").port(502).build();
 * // 配置入口（协议由 ModbusSetting 选择）
 * ModbusServer server = ModbusServer.create(ModbusSetting.of("0.0.0.0", 502));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"modbus", "modbus-tcp"})
public class ModbusTcpServer extends AbstractModbusSlaveServer {

    /**
     * 默认连接处理线程数
     */
    public static final int DEFAULT_POOL_SIZE = 8;

    /**
     * 连接处理线程数
     */
    private volatile int poolSize = DEFAULT_POOL_SIZE;

    /**
     * 创建服务端（common Server SPI 入口）。
     *
     * @param setting 服务器配置，端口通常为 502
     */
    public ModbusTcpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 创建服务端（ModbusServer SPI 入口）。
     *
     * @param setting Modbus 配置，协议应为 TCP
     */
    public ModbusTcpServer(ModbusSetting setting) {
        super(toServerSetting(setting));
        setUnitId(setting.getUnitId());
    }

    @Override
    protected ModbusSlave createSlave(InetAddress address, int port) throws Exception {
        return ModbusSlaveFactory.createTCPSlave(address, port, Math.max(1, poolSize), false);
    }

    /**
     * 获取连接处理线程数。
     *
     * @return 线程数
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * 设置连接处理线程数，需在 {@link #start()} 之前调用。
     *
     * @param poolSize 线程数，最小 1
     */
    public void setPoolSize(int poolSize) {
        this.poolSize = Math.max(1, poolSize);
    }
}
