package com.chua.modbus.support.client;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.modbus.support.ModbusSetting;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;

/**
 * Modbus TCP 客户端（j2mod 主站实现）。
 *
 * <p>通过 MBAP 报文与从站通信，事务号、超时、重试与异常响应均由 j2mod 处理：
 * 调用方只需要给出从站地址、寄存器地址与数量。</p>
 *
 * <p>SPI 别名：{@code modbus-tcp}、{@code modbus}。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"modbus-tcp", "modbus"})
public class ModbusTcpClient extends AbstractJ2ModClient {

    /**
     * 创建使用默认配置的客户端。
     */
    public ModbusTcpClient() {
        this(ModbusSetting.defaults());
    }

    /**
     * 创建客户端。
     *
     * @param setting 客户端配置
     */
    public ModbusTcpClient(ModbusSetting setting) {
        super(setting);
    }

    @Override
    protected AbstractModbusMaster createMaster() {
        ModbusSetting setting = getSetting();
        setting.validateNetwork();
        // reconnect=false：保持长连接；断线由基类复位后按需重连，避免每个事务都重新握手
        return new ModbusTCPMaster(setting.getHost(), setting.getPort(), setting.getReadTimeout(), false);
    }

    @Override
    protected String describeEndpoint() {
        ModbusSetting setting = getSetting();
        return "modbus-tcp://" + setting.getHost() + ":" + setting.getPort();
    }
}
