// [P3C A20 豁免] A20/A30/A31: 协议名与端口来自 Modbus 规范/部署约定。
package com.chua.modbus.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.modbus.support.ModbusException;
import com.chua.modbus.support.ModbusSetting;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;

/**
 * 网络型 Modbus 从站服务端基类。
 *
 * <p>把 j2mod 从站的生命周期、过程映像注册与数据区管理收敛在这里，
 * 传输差异（TCP/UDP）只由子类实现 {@link #createSlave(InetAddress, int)}。</p>
 *
 * <p>服务端支持两种构造来源：</p>
 * <ul>
 *   <li>{@code ServerSetting}：供 common-starter 的 {@code Server} SPI（{@code ServerBuilder}）使用；</li>
 *   <li>{@code ModbusSetting}：供本模块 {@code ModbusServer} SPI 使用，协议由配置选择。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public abstract class AbstractModbusSlaveServer extends AbstractServer implements ModbusServer {

    /**
     * j2mod 从站
     */
    protected volatile ModbusSlave slave;

    /**
     * 过程映像
     */
    protected volatile DataStoreProcessImage processImage;

    /**
     * 数据区
     */
    protected volatile ModbusDataStore dataStore = new MemoryModbusDataStore();

    /**
     * 从站地址
     */
    protected volatile int unitId = ModbusSetting.DEFAULT_UNIT_ID;

    /**
     * 创建服务端。
     *
     * @param setting 服务器配置
     */
    protected AbstractModbusSlaveServer(ServerSetting setting) {
        super(setting);
        this.setting.setProtocol("modbus");
    }

    /**
     * 创建 j2mod 从站。
     *
     * @param address 监听地址
     * @param port    监听端口
     * @return 从站实例
     * @throws Exception 创建失败
     */
    protected abstract ModbusSlave createSlave(InetAddress address, int port) throws Exception;

    /**
     * 把 Modbus 配置转换为网络服务器配置。
     *
     * @param modbusSetting Modbus 配置
     * @return 服务器配置
     * @throws ModbusException 配置非法或协议为串口时抛出
     */
    protected static ServerSetting toServerSetting(ModbusSetting modbusSetting) {
        if (modbusSetting == null) {
            throw new ModbusException("Modbus 服务端配置不能为空");
        }
        if (modbusSetting.getProtocol() != null && modbusSetting.getProtocol().isSerial()) {
            throw new ModbusException("串口协议不能用于网络服务端: " + modbusSetting.getProtocol());
        }
        modbusSetting.validate();
        ServerSetting serverSetting = ServerSetting.defaults();
        serverSetting.setHost(modbusSetting.getHost());
        serverSetting.setPort(modbusSetting.getPort());
        return serverSetting;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.MODBUS;
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

    @Override
    protected void doStart() {
        rejectStreamEncryption("Modbus/TCP 无密钥协商字段，本实现也未接入 Modbus Security(TLS)");
        try {
            InetAddress address = InetAddress.getByName(setting.getHost());
            ModbusSlave created = createSlave(address, setting.getPort());
            DataStoreProcessImage image = new DataStoreProcessImage(dataStore);
            created.addProcessImage(unitId, image);
            created.open();
            this.slave = created;
            this.processImage = image;
            this.setting.setPort(created.getPort());
            this.running = true;
            log.info("{} 启动: {}:{} (unitId={})",
                    getClass().getSimpleName(), setting.getHost(), setting.getPort(), unitId);
        } catch (Exception e) {
            this.running = false;
            closeSlave();
            throw new ModbusException(getClass().getSimpleName() + " 启动失败: "
                    + setting.getHost() + ":" + setting.getPort() + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    protected void doStop() {
        this.running = false;
        closeSlave();
        log.info("{} 停止: {}:{}", getClass().getSimpleName(), setting.getHost(), setting.getPort());
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
            log.debug("{} 关闭从站异常，已忽略: {}", getClass().getSimpleName(), e.getMessage());
        }
    }
}
