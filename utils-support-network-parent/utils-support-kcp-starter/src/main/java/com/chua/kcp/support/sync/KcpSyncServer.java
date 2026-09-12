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

    @Override
    /** 获取协议 */
    public String getProtocol() {
        return "kcp";
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.KCP;
    }

    @Override
    /** 创建服务端 */
    public SyncServer createServer(ServerSetting setting) {
        return new KcpSyncServer(setting);
    }

    @Override
    /** 创建客户端 */
    public SyncClient createClient(Object setting) {
        String url = setting instanceof String ? (String) setting : DEFAULT_URL;
        return new KcpSyncClient(url);
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        delegate.start();
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        delegate.stop();
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object message) {
        delegate.publish(topic, message);
    }

    @Override
    /** 发送 */
    public void send(String clientId, String topic, Object message) {
        delegate.send(clientId, topic, message);
    }

    @Override
    /** 获取连接客户端 */
    public List<String> getConnectedClients() {
        return delegate.getConnectedClients();
    }

    @Override
    /** 获取客户端metadata */
    public Map<String, Object> getClientMetadata(String clientId) {
        return delegate.getClientMetadata(clientId);
    }

    @Override
    /** 添加监听器 */
    public void addListener(SyncServerListener listener) {
        delegate.addListener(listener);
    }

    @Override
    /** 移除监听器 */
    public void removeListener(SyncServerListener listener) {
        delegate.removeListener(listener);
    }
}
