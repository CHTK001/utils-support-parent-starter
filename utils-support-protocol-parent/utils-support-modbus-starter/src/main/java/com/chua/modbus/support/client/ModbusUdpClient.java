package com.chua.modbus.support.client;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.modbus.support.ModbusSetting;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusUDPMaster;

/**
 * Modbus UDP 客户端（j2mod UDP 主站实现）。
 *
 * <p>报文结构与 Modbus TCP 相同，但不建立连接，每个请求是一个独立数据报；
 * 适合低时延、可容忍丢包的局域网采集。</p>
 *
 * <p>SPI 别名：{@code modbus-udp}、{@code udp}。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"modbus-udp", "udp"})
public class ModbusUdpClient extends AbstractJ2ModClient {

    /**
     * 创建使用默认配置的客户端。
     */
    public ModbusUdpClient() {
        this(ModbusSetting.defaults());
    }

    /**
     * 创建客户端。
     *
     * @param setting 客户端配置
     */
    public ModbusUdpClient(ModbusSetting setting) {
        super(setting);
    }

    @Override
    protected AbstractModbusMaster createMaster() {
        ModbusSetting setting = getSetting();
        setting.validateTcp();
        return new ModbusUDPMaster(setting.getHost(), setting.getPort(), setting.getReadTimeout());
    }

    @Override
    protected String describeEndpoint() {
        ModbusSetting setting = getSetting();
        return "modbus-udp://" + setting.getHost() + ":" + setting.getPort();
    }
}
