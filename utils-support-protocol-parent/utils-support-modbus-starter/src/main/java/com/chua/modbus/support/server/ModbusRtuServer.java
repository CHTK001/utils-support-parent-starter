package com.chua.modbus.support.server;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.modbus.support.ModbusException;
import com.chua.modbus.support.ModbusSetting;
import com.chua.modbus.support.SerialParameterUtils;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Modbus 串口服务端（j2mod 串口从站实现）。
 *
 * <p>用于把本机模拟成 RTU/ASCII 从站：串口参数取 {@link ModbusSetting}，
 * 请求处理与 CRC/LRC 校验由 j2mod 完成。</p>
 *
 * <pre>{@code
 * ModbusRtuServer server = new ModbusRtuServer(ModbusSetting.ofSerial("COM3").setBaudRate(19200));
 * server.getDataStore().writeHoldingRegisters(0, new int[]{100});
 * server.start();
 * }</pre>
 *
 * <p>串口是独占资源，同一端口同时只能有一个从站实例。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi({"modbus-rtu", "rtu", "modbus-ascii", "ascii"})
public class ModbusRtuServer implements ModbusServer {

    /**
     * 串口配置
     */
    private final ModbusSetting setting;

    /**
     * j2mod 从站
     */
    private volatile ModbusSlave slave;

    /**
     * 过程映像
     */
    private volatile DataStoreProcessImage processImage;

    /**
     * 数据区
     */
    private volatile ModbusDataStore dataStore = new MemoryModbusDataStore();

    /**
     * 从站地址
     */
    private volatile int unitId;

    /**
     * 运行状态
     */
    private volatile boolean running;

    /**
     * 创建使用默认配置的服务端。
     */
    public ModbusRtuServer() {
        this(ModbusSetting.defaults());
    }

    /**
     * 创建服务端。
     *
     * @param setting 串口配置，必须包含串口名称
     */
    public ModbusRtuServer(ModbusSetting setting) {
        this.setting = setting == null ? ModbusSetting.defaults() : setting;
        this.unitId = this.setting.getUnitId();
    }

    /**
     * 获取串口配置。
     *
     * @return 配置实例
     */
    public ModbusSetting getSetting() {
        return setting;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (setting.getProtocol() != null && !setting.getProtocol().isSerial()) {
            throw new ModbusException("ModbusRtuServer 需要 RTU/ASCII 串口协议: " + setting.getProtocol());
        }
        try {
            ModbusSlave created = ModbusSlaveFactory.createSerialSlave(
                    SerialParameterUtils.toSerialParameters(setting));
            DataStoreProcessImage image = new DataStoreProcessImage(dataStore);
            created.addProcessImage(unitId, image);
            created.open();
            this.slave = created;
            this.processImage = image;
            this.running = true;
            log.info("Modbus 串口服务器启动: {}@{} (unitId={}, encoding={})",
                    setting.getPortName(), setting.getBaudRate(), unitId, setting.getSerialEncoding());
        } catch (Exception e) {
            this.running = false;
            closeSlave();
            throw new ModbusException("Modbus 串口服务器启动失败: " + setting.getPortName()
                    + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        this.running = false;
        closeSlave();
        log.info("Modbus 串口服务器停止: {}", setting.getPortName());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public ModbusDataStore getDataStore() {
        return dataStore;
    }

    @Override
    public void setDataStore(ModbusDataStore dataStore) {
        if (dataStore == null) {
            return;
        }
        this.dataStore = dataStore;
        DataStoreProcessImage current = processImage;
        if (current != null) {
            current.setDataStore(dataStore);
        }
    }

    @Override
    public int getUnitId() {
        return unitId;
    }

    @Override
    public void setUnitId(int unitId) {
        if (unitId < 0 || unitId > ModbusSetting.MAX_UNIT_ID) {
            throw new IllegalArgumentException("Modbus 从站地址非法: " + unitId);
        }
        this.unitId = unitId;
    }

    /**
     * 关闭 j2mod 从站并解除过程映像注册。
     */
    private void closeSlave() {
        ModbusSlave current = this.slave;
        this.slave = null;
        this.processImage = null;
        if (current == null) {
            return;
        }
        try {
            current.removeProcessImage(unitId);
            current.close();
        } catch (RuntimeException e) {
            log.debug("Modbus 串口关闭从站异常，已忽略: {}", e.getMessage());
        }
    }
}
