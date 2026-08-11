package com.chua.common.support.taskdistribution.scattergather;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.scattergather.ScatterGatherNodeServer;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * UDP 实现 ScatterGatherNodeServer。
 *
 * <p>包装 {@link com.chua.common.support.network.sync.impl.UdpSyncServer}，
 * 通过 SPI 创建 "udp" 类型的 SyncServer 实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UdpScatterGatherNodeServer implements ScatterGatherNodeServer {

    /**
     * 底层 SyncServer
     */
    private final SyncServer syncServer;

    /**
     * 服务器配置
     */
    private final ServerSetting setting;

    /**
     * 构建 UDP ScatterGather 节点服务器。
     *
     * @param port 端口号
     */
    public UdpScatterGatherNodeServer(int port) {
        this.setting = ServerSetting.defaults();
        this.setting.setPort(port);
        this.setting.setProtocol("udp");
        this.syncServer = ServiceProvider.of(SyncServer.class).getNewExtension("udp", setting);
    }

    /**
     * 构建 UDP ScatterGather 节点服务器。
     *
     * @param host 主机地址
     * @param port 端口号
     */
    public UdpScatterGatherNodeServer(String host, int port) {
        this.setting = ServerSetting.defaults();
        this.setting.setHost(host);
        this.setting.setPort(port);
        this.setting.setProtocol("udp");
        this.syncServer = ServiceProvider.of(SyncServer.class).getNewExtension("udp", setting);
    }

    /**
     * 注册消息处理器。
     *
     * @param topic   主题
     * @param handler 处理器
     */
    public void registerHandler(String topic, SyncMessageHandler handler) {
        if (syncServer != null) {
            syncServer.addListener(new SyncMessageListenerAdapter(topic, handler));
        }
    }

    @Override
    public void start() throws Exception {
        if (syncServer != null) {
            syncServer.start();
            log.info("UDP ScatterGather 节点服务器已启动: {}:{}", setting.getHost(), setting.getPort());
        }
    }

    @Override
    public void stop() throws Exception {
        if (syncServer != null) {
            syncServer.stop();
            log.info("UDP ScatterGather 节点服务器已停止");
        }
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    /**
     * 获取底层 SyncServer。
     *
     * @return SyncServer 实例
     */
    public SyncServer getSyncServer() {
        return syncServer;
    }
}