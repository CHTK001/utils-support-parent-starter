package com.chua.rsocket.support.agent;

import com.chua.datasync.agent.support.DataSyncAgent;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.common.support.lang.json.Json;
import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * RSocket 数据同步 Agent
 * <p>通过 RSocket 与 DataSyncServer 建立双向流式连接。</p>
 *
 * <pre>{@code
 * RSocketDataSyncAgent agent = new RSocketDataSyncAgent(
 *         "agent-1", "source-1", "localhost", 8080);
 * agent.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RSocketDataSyncAgent implements DataSyncAgent {

    /**
     * agent Id
     */
    private final String agentId;
    /**
     * source Id
     */
    private final String sourceId;
    /**
     * 主机地址
     */
    private final String host;
    /**
     * 端口号
     */
    private final int port;
    /**
     * source
     */
    private final DataSyncSource source;

    /**
     * r Socket
     */
    private RSocket rSocket;
    /**
     * running
     */
    private volatile boolean running = false;

    public RSocketDataSyncAgent(String agentId, String sourceId, String host, int port, DataSyncSource source) {
        this.agentId = agentId;
        this.sourceId = sourceId;
        this.host = host;
        this.port = port;
        this.source = source;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        rSocket = RSocketConnector.create()
                .keepAlive(Duration.ofSeconds(30), Duration.ofSeconds(10))
                .connect(TcpClientTransport.create(host, port))
                .block();

        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (rSocket != null) {
            rSocket.dispose();
            rSocket = null;
        }
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public DataSyncSource toSource() {
        return source;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String dataUrl() {
        return "tcp://" + host + ":" + port;
    }

    public RSocket getRSocket() {
        return rSocket;
    }
}
