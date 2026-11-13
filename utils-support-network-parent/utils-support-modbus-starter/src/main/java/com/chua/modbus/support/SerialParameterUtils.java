package com.chua.modbus.support;

import com.ghgande.j2mod.modbus.util.SerialParameters;

/**
 * 串口参数转换器。
 *
 * <p>把框架无关的 {@link ModbusSetting} 转换为 j2mod 的 {@link SerialParameters}，
 * 供 RTU/ASCII 主站与从站共用，避免两端各写一份映射。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SerialParameterUtils {

    /**
     * 工具类不允许实例化。
     */
    private SerialParameterUtils() {
    }

    /**
     * 转换串口参数。
     *
     * @param setting Modbus 配置
     * @return j2mod 串口参数
     * @throws ModbusException 串口配置非法时抛出
     */
    public static SerialParameters toSerialParameters(ModbusSetting setting) {
        setting.validateSerial();
        SerialParameters parameters = new SerialParameters();
        parameters.setPortName(setting.getPortName());
        parameters.setBaudRate(setting.getBaudRate());
        parameters.setDatabits(setting.getDataBits());
        parameters.setStopbits(setting.getStopBits());
        parameters.setParity(setting.getParity());
        parameters.setEncoding(setting.getSerialEncoding());
        parameters.setEcho(setting.isEcho());
        parameters.setOpenDelay(setting.getOpenDelay());
        parameters.setRs485Mode(setting.isRs485Mode());
        parameters.setRs485TxEnableActiveHigh(setting.isRs485TxEnableActiveHigh());
        parameters.setRs485EnableTermination(setting.isRs485EnableTermination());
        parameters.setRs485RxDuringTx(setting.isRs485RxDuringTx());
        return parameters;
    }
}
