package com.chua.modbus.support.server;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.modbus.support.ModbusException;
import com.chua.modbus.support.ModbusProtocol;
import com.chua.modbus.support.ModbusSetting;

/**
 * Modbus 服务端（从站）SPI。
 *
 * <p>只约束生命周期与数据区，不绑定传输方式；实现通过
 * {@code META-INF/extensions/com.chua.modbus.support.server.ModbusServer} 以别名注册：</p>
 * <ul>
 *   <li>{@code modbus-tcp}：{@link ModbusTcpServer}，同时登记到 common-starter 的 {@code Server} SPI</li>
 *   <li>{@code modbus-udp}：{@link ModbusUdpServer}，同时登记到 common-starter 的 {@code Server} SPI</li>
 *   <li>{@code modbus-rtu}：{@link ModbusRtuServer}</li>
 *   <li>{@code modbus-ascii}：{@link ModbusRtuServer}（串口 ASCII 编码）</li>
 * </ul>
 *
 * <p>使用方式（由配置的 {@link ModbusSetting#getProtocol()} 选择实现）：</p>
 * <pre>{@code
 * ModbusServer server = ModbusServer.create(ModbusSetting.of("0.0.0.0", 502)
 *         .setProtocol(ModbusProtocol.UDP));
 * server.getDataStore().writeHoldingRegisters(0, new int[]{1, 2, 3});
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ModbusServer extends AutoCloseable {

    /**
     * 按配置中的协议创建服务端。
     *
     * <p>{@link ModbusProtocol#TCP}、{@link ModbusProtocol#UDP}、{@link ModbusProtocol#RTU}、
     * {@link ModbusProtocol#ASCII} 分别映射到 {@code modbus-tcp} / {@code modbus-udp} /
     * {@code modbus-rtu} / {@code modbus-ascii}。</p>
     *
     * @param setting Modbus 配置
     * @return 服务端实例
     * @throws ModbusException 配置非法或未找到实现时抛出
     */
    static ModbusServer create(ModbusSetting setting) {
        if (setting == null) {
            throw new ModbusException("Modbus 服务端配置不能为空");
        }
        setting.validate();
        ModbusServer server = ServiceProvider.of(ModbusServer.class)
                .getNewExtension(setting.getProtocol().getSpiType(), setting);
        if (server == null) {
            throw new ModbusException("未找到 Modbus 服务端实现: " + setting.getProtocol().getSpiType());
        }
        return server;
    }

    /**
     * 启动服务端。
     *
     * @throws ModbusException 启动失败时抛出
     */
    void start();

    /**
     * 停止服务端。
     */
    void stop();

    /**
     * 判断服务端是否正在运行。
     *
     * @return 运行中返回 {@code true}
     */
    boolean isRunning();

    /**
     * 获取数据区。
     *
     * @return 数据区实例
     */
    ModbusDataStore getDataStore();

    /**
     * 替换数据区。
     *
     * <p>运行中也可以替换：服务端持有的过程映像会立即切换到新数据区。</p>
     *
     * @param dataStore 数据区，为 {@code null} 时忽略
     */
    void setDataStore(ModbusDataStore dataStore);

    /**
     * 获取从站地址。
     *
     * @return 从站地址
     */
    int getUnitId();

    /**
     * 设置从站地址。
     *
     * <p>需在 {@link #start()} 之前调用；运行中修改不会重建过程映像。</p>
     *
     * @param unitId 从站地址，0~255
     */
    void setUnitId(int unitId);

    /**
     * 关闭服务端。
     */
    @Override
    default void close() {
        stop();
    }
}
