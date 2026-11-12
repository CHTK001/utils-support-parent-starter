package com.chua.modbus.support.client;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.modbus.support.ModbusException;
import com.chua.modbus.support.ModbusSetting;
import com.chua.modbus.support.SerialParameterUtils;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;

/**
 * Modbus 串口客户端（j2mod 串口主站实现）。
 *
 * <p>支持 RTU 与 ASCII 两种串口编码，由 {@link ModbusSetting#getSerialEncoding()} 选择；
 * 波特率、数据位、停止位、校验位与 RS-485 选项同样来自配置对象。</p>
 *
 * <p>SPI 别名：{@code modbus-rtu}、{@code rtu}。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"modbus-rtu", "rtu", "modbus-ascii", "ascii"})
public class ModbusRtuClient extends AbstractJ2ModClient {

    /**
     * 创建使用默认配置的客户端。
     */
    public ModbusRtuClient() {
        this(ModbusSetting.defaults());
    }

    /**
     * 创建客户端。
     *
     * @param setting 客户端配置
     */
    public ModbusRtuClient(ModbusSetting setting) {
        super(setting);
    }

    @Override
    protected AbstractModbusMaster createMaster() {
        ModbusSetting setting = getSetting();
        if (setting.getProtocol() != null && !setting.getProtocol().isSerial()) {
            throw new ModbusException("ModbusRtuClient 需要 RTU/ASCII 串口协议: " + setting.getProtocol());
        }
        // 构造期完成串口参数校验与转换；实际打开串口在 connect() 时发生
        return new ModbusSerialMaster(SerialParameterUtils.toSerialParameters(setting), setting.getReadTimeout());
    }

    @Override
    protected String describeEndpoint() {
        ModbusSetting setting = getSetting();
        return "modbus-" + setting.getSerialEncoding() + "://" + setting.getPortName()
                + "@" + setting.getBaudRate();
    }
}
