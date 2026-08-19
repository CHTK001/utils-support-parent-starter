package com.chua.kcp.support.scatter;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncMessageHandler;
import com.chua.common.support.scatter.ScatterNodeServer;
import com.chua.common.support.scatter.ScatterSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

/**
 * KCP 实现 ScatterNodeServer。
 *
 * <p>包装 {@link com.chua.kcp.support.sync.KcpSyncServer}，
 * 通过 SPI 创建 "kcp" 类型的 SyncServer 实例，提供可靠 UDP 长连接双向同步。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("kcp")
public class KcpScatterNodeServer implements ScatterNodeServer {

    /**
     * 传输协议标识：kcp
     */
    private static final String PROTOCOL_KCP = "kcp";

    /**
     * 底层 SyncServer
     */
    private final SyncServer syncServer;

    /**
     * 服务器配置
     */
    private final ServerSetting setting;

    /**
     * 构建 KCP Scatter 节点服务器。
     *
     * @param setting 节点配置
     */
    public KcpScatterNodeServer(ScatterSetting setting) {
        this(setting == null ? null : setting.getHost(), setting == null ? 0 : setting.getPort());
    }

    /**
     * 构建 KCP Scatter 节点服务器。
     *
     * @param host 主机地址
     * @param port 端口号
     */
    public KcpScatterNodeServer(String host, int port) {
        this.setting = ServerSetting.defaults();
        this.setting.setHost(host);
        this.setting.setPort(port);
        this.setting.setProtocol(PROTOCOL_KCP);
        this.syncServer = ServiceProvider.of(SyncServer.class).getNewExtension(PROTOCOL_KCP, setting);
    }

    /**
     * 注册消息处理器。
     *
     * @param topic   主题
     * @param handler 处理器
     */
    @Override
    public void registerHandler(String topic, SyncMessageHandler handler) {
        if (syncServer != null) {
            syncServer.addListener(new SyncServerListener() {
                @Override
                /** OnMessage */
                public void onMessage(String clientId, String messageTopic, Object message) {
                    if (topic == null || topic.equals(messageTopic)) {
                        handler.handle(topic != null ? topic : messageTopic, message);
                    }
                }
            });
        }
    }

    /**
     * 获取底层 SyncServer。
     *
     * @return SyncServer 实例
     */
    @Override
    public SyncServer getSyncServer() {
        return syncServer;
    }

    /**
     * 启动节点服务器。
     *
     * @throws Exception 启动异常
     */
    @Override
    public void start() throws Exception {
        if (syncServer != null) {
            syncServer.start();
            log.info("KCP Scatter 节点服务器已启动: {}:{}", setting.getHost(), setting.getPort());
        }
    }

    /**
     * 停止节点服务器。
     *
     * @throws Exception 停止异常
     */
    @Override
    public void stop() throws Exception {
        if (syncServer != null) {
            syncServer.stop();
            log.info("KCP Scatter 节点服务器已停止");
        }
    }

    /**
     * 关闭节点服务器，委托 stop。
     *
     * @throws Exception 关闭异常
     */
    @Override
    public void close() throws Exception {
        stop();
    }
}
