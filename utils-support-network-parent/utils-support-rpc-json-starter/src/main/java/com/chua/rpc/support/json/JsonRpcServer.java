package com.chua.rpc.support.json;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ThreadUtils;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;
import com.googlecode.jsonrpc4j.JsonRpcMultiServer;
import com.googlecode.jsonrpc4j.StreamServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-RPC 2.0 服务端实现。
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("json")
@Slf4j
public class JsonRpcServer implements RpcServer {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_THREADS = 50;

    private final ExecutorService runService = ThreadUtils.newFixedThreadExecutor(2, "json-rpc");
    private final AtomicBoolean state = new AtomicBoolean(false);
    private final RpcProtocolConfig protocolConfig;
    private JsonRpcMultiServer rpcServer;
    private StreamServer streamServer;
    private ServerSocket serverSocket;

    public JsonRpcServer(List<RpcRegistryConfig> rpcRegistryConfigs, RpcProtocolConfig protocolConfig, String name) {
        this.protocolConfig = protocolConfig;
    }

    @Override
    public RpcServer register(String name, Object bean) {
        if (rpcServer != null) {
            rpcServer.addService(name, bean);
            log.info("Registered JSON-RPC service: {}", name);
        } else {
            log.warn("JsonRpcServer not started, cannot register: {}", name);
        }
        return this;
    }

    @Override
    public void afterPropertiesSet() {
        if (state.compareAndSet(false, true)) {
            runService.execute(() -> {
                try {
                    this.rpcServer = new JsonRpcMultiServer();
                    int port = protocolConfig != null && protocolConfig.port() != null ? protocolConfig.port() : DEFAULT_PORT;
                    int maxThreads = protocolConfig != null && protocolConfig.threads() != null ? protocolConfig.threads() : DEFAULT_THREADS;
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    streamServer = new StreamServer(rpcServer, maxThreads, serverSocket);
                    streamServer.start();
                    log.info("JsonRpcServer started: port={}, threads={}", port, maxThreads);
                } catch (Exception e) {
                    log.error("JsonRpcServer start failed", e);
                    state.set(false);
                }
            });
        }
    }

    @Override
    public void close() {
        state.set(false);
        if (streamServer != null) {
            try { streamServer.stop(); } catch (Exception ignored) {}
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        ThreadUtils.closeQuietly(runService);
        log.info("JsonRpcServer closed");
    }
}