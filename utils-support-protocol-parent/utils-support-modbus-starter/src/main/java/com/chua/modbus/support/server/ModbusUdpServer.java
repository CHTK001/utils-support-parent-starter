package com.chua.modbus.support.server;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.modbus.support.ModbusSetting;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import java.net.InetAddress;

/**
 * Modbus UDP 服务端（j2mod UDP 从站实现）。
 *
 * <p>报文结构与 TCP 相同（MBAP），但没有连接：j2mod 用单线程接收数据报并逐个应答，
 * 适合局域网采集，不适合高并发；需要并发处理时仍应选 TCP。</p>
 *
 * <pre>{@code
 * ModbusServer server = ModbusServer.create(ModbusSetting.of("0.0.0.0", 502)
 *         .setProtocol(ModbusProtocol.UDP));
 * server.start();
 * }</pre>
 *
 * <p>SPI 别名：{@code modbus-udp}、{@code udp}；同时也登记到 common-starter 的 {@code Server} SPI。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Spi({"modbus-udp", "udp"})
public class ModbusUdpServer extends AbstractModbusSlaveServer {

    /**
     * 创建服务端（common Server SPI 入口）。
     *
     * @param setting 服务器配置
     */
    public ModbusUdpServer(ServerSetting setting) {
        super(setting);
    }

    /**
     * 创建服务端（ModbusServer SPI 入口）。
     *
     * @param setting Modbus 配置，协议应为 UDP
     */
    public ModbusUdpServer(ModbusSetting setting) {
        super(toServerSetting(setting));
        setUnitId(setting.getUnitId());
    }

    @Override
    protected ModbusSlave createSlave(InetAddress address, int port) throws Exception {
        return ModbusSlaveFactory.createUDPSlave(address, port);
    }

    @Override
    protected void doStop() {
        interruptListener();
        super.doStop();
    }

    /**
     * 中断阻塞在接收队列上的 j2mod UDP 监听线程。
     *
     * <p>j2mod 3.2.1 的 UDP 监听线程阻塞在 {@code LinkedBlockingQueue.take()}，
     * 而 {@code stop()} 只关闭套接字、不唤醒该队列，导致 {@code ModbusSlave.close()}
     * 固定等待 5 秒后调用废弃的 {@code Thread.stop()}。这里按线程名（含端口）与调用栈
     * 精确定位本实例的监听线程并中断，让其从 {@code take()} 抛出中断异常后退出循环。</p>
     */
    private void interruptListener() {
        String expectedName = "Modbus UDP Listener [port:" + setting.getPort() + "]";
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (expectedName.equals(thread.getName()) && waitsInUdpReceive(thread.getStackTrace())) {
                thread.interrupt();
            }
        }
    }

    /**
     * 判断线程是否阻塞在 j2mod UDP 接收队列。
     *
     * @param stack 线程调用栈
     * @return 命中返回 {@code true}
     */
    private static boolean waitsInUdpReceive(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            if ("com.ghgande.j2mod.modbus.net.UDPSlaveTerminal".equals(element.getClassName())
                    && "receiveMessage".equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}

