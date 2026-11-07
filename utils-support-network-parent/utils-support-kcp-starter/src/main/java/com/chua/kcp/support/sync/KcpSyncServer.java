package com.chua.kcp.support.sync;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.SyncServer;
import com.chua.common.support.network.server.SyncServerListener;
import com.chua.common.support.network.sync.SyncClient;
import com.chua.common.support.network.sync.SyncProtocol;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.kcp.support.server.KcpServer;

import java.util.List;
import java.util.Map;

/**
 * KCP 同步服务端实现，基于 kcp-Netty 提供可靠 UDP 长连接双向同步能力。
 *
 * <p>委托给 {@link KcpServer} 处理底层 KCP 通信与消息分发，
 * 通过 SPI 以 {@code "kcp"} 类型注册，与 {@link KcpSyncClient} 配对使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("kcp")
public class KcpSyncServer extends AbstractServer implements SyncServer, SyncProtocol {

    /**
     * 默认客户端连接地址
     */
    private static final String DEFAULT_URL = "kcp://127.0.0.1:19380";

    /**
     * 底层 KCP 消息服务器
     */
    private final KcpServer delegate;

    /**
     * 创建 KCP 同步服务端。
     *
     * @param setting 服务端配置
     */
    public KcpSyncServer(ServerSetting setting) {
        super(setting);
        this.delegate = new KcpServer(setting);
    }

    /**
     * 获取协议
    */
    @Override
    public String getProtocol() {
        return "kcp";
    }

    /**
     * 获取协议类型
    */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.KCP;
    }

    /**
     * 创建服务端
    */
    @Override
    public SyncServer createServer(ServerSetting setting) {
        return new KcpSyncServer(setting);
    }

    /**
     * 创建客户端
    */
    @Override
    public SyncClient createClient(Object setting) {
        String url = com.chua.common.support.network.sync.SyncAddressResolver.resolveAddress(setting, DEFAULT_URL);
        return new KcpSyncClient(url);
    }

    /**
     * 执行开始
    */
    @Override
    protected void doStart() {
        delegate.start();
    }

    /**
     * 执行停止
    */
    @Override
    protected void doStop() {
        delegate.stop();
    }

    /**
     * 发布
    */
    @Override
    public void publish(String topic, Object message) {
        delegate.publish(topic, message);
    }

    /**
     * 发送
    */
    @Override
    public void send(String clientId, String topic, Object message) {
        delegate.send(clientId, topic, message);
    }

    /**
     * 获取连接客户端
    */
    @Override
    public List<String> getConnectedClients() {
        return delegate.getConnectedClients();
    }

    /**
     * 获取客户端metadata
    */
    @Override
    public Map<String, Object> getClientMetadata(String clientId) {
        return delegate.getClientMetadata(clientId);
    }

    /**
     * 添加监听器
    */
    @Override
    public void addListener(SyncServerListener listener) {
        delegate.addListener(listener);
    }

    /**
     * 移除监听器
    */
    @Override
    public void removeListener(SyncServerListener listener) {
        delegate.removeListener(listener);
    }
}
