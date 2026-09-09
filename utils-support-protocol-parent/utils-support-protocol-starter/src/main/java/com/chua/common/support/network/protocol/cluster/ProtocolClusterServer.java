package com.chua.common.support.network.protocol.cluster;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.protocol.ServerSetting;
import com.chua.common.support.network.protocol.server.AbstractProtocolServer;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.network.protocol.server.ServletHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 基于协议类型和端口的集群服务器实现
 * <p>
 * 管理基于协议类型和端口的集群服务器，支持多端口部署。
 *
 * @author CH
 * @since 4.0.0.30
 */
@Slf4j
@Getter
public class ProtocolClusterServer  extends AbstractProtocolServer implements ClusterServer {

    /** 集群实例 */
    private final Cluster cluster;

    /** 协议类型名称 */
    private final String protocol;

    /** 服务器设置 */
    private final ServerSetting serverSetting;

    /** 端口列表 */
    private final int[] ports;

    /** 协议服务器实例 */
    private List<ProtocolServer> protocolServers = new ArrayList<>();

    /** 运行状态 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造函数
     *
     * @param cluster 集群实例
     * @param ports 端口列表
     */
    public ProtocolClusterServer(Cluster cluster, int... ports) {
        this(cluster, "http", ServerSetting.builder()
                .port(0)
                .build(), ports);
    }

    /**
     * 构造函数
     *
     * @param cluster 集群实例
     * @param serverSetting 服务器设置
     * @param ports 端口列表
     */
    public ProtocolClusterServer(Cluster cluster, ServerSetting serverSetting, int... ports) {
        this(cluster, "http", serverSetting, ports);
    }
    /**
     * 构造函数
     *
     * @param cluster 集群实例
     * @param protocolType 协议类型
     * @param serverSetting 服务器设置
     * @param ports 端口列表
     */
    public ProtocolClusterServer(Cluster cluster, String protocolType, ServerSetting serverSetting, int... ports) {
        super(serverSetting);
        if (cluster == null) {
            throw new IllegalArgumentException("集群实例不能为空");
        }
        if (protocolType == null || protocolType.trim().isEmpty()) {
            throw new IllegalArgumentException("协议类型不能为空");
        }
        this.protocol = protocolType.trim();
        if (serverSetting == null) {
            throw new IllegalArgumentException("服务器设置不能为空");
        }
        if (ports == null || ports.length == 0) {
            throw new IllegalArgumentException("端口列表不能为空");
        }

        this.cluster = cluster;
        this.serverSetting = serverSetting;
        this.ports = ports.clone();

        if (log.isDebugEnabled()) {
            log.debug("创建协议集群服务器: 协议={}, 端口={}", this.protocol, java.util.Arrays.toString(this.ports));
        }
    }



    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    protected void doStart() throws Exception {
        if (running.compareAndSet(false, true)) {
            try {
                log.info("启动协议集群服务器: 协议={}, 端口={}", protocol, java.util.Arrays.toString(ports));

                // 1. 首先启动集群
                if (!cluster.isRunning()) {
                    cluster.start();
                    if (log.isDebugEnabled()) {
                        log.debug("集群启动完成: {}", cluster.getClusterName());
                    }
                }

                // 2. 启动指定协议类型的服务端
                for (int port : ports) {
                    try {
                        ProtocolServer protocolServer = createProtocolServer(port);
                        if (protocolServer != null && !protocolServer.isRunning()) {
                            protocolServer.start();
                        }
                        protocolServers.add(protocolServer);
                        log.info("协议集群服务器启动成功: 协议={}, 端口={}", protocol, port);
                    } catch (Exception e) {
                        running.set(false);
                        log.error("协议集群服务器启动失败: 协议={}, 端口={}", protocol, port, e);

                        // 清理已启动的资源
                        cleanup();
                        throw e;
                    }
                }


            } catch (Exception e) {
                running.set(false);
                log.error("协议集群服务器启动失败: 协议={}, 端口={}", protocol, java.util.Arrays.toString(ports), e);

                // 清理已启动的资源
                cleanup();
                throw e;
            }
        } else {
            log.warn("协议集群服务器已经在运行中: 协议={}", protocol);
        }
    }

    @Override
    protected void doStop() throws Exception {
        if (running.compareAndSet(true, false)) {
            try {
                log.info("停止协议集群服务器: 协议={}, 端口={}", protocol, java.util.Arrays.toString(ports));

                // 按照启动的逆序进行停止操作

                // 1. 停止协议服务器
                for (ProtocolServer protocolServer : protocolServers) {
                    if (protocolServer != null && protocolServer.isRunning()) {
                        protocolServer.stop();
                        if (log.isDebugEnabled()) {
                            log.debug("协议服务器停止完成: {}", protocol);
                        }
                    }
                }

                // 2. 停止集群
                if (cluster.isRunning()) {
                    cluster.stop();
                    if (log.isDebugEnabled()) {
                        log.debug("集群停止完成: {}", cluster.getClusterName());
                    }
                }

                log.info("协议集群服务器停止成功: 协议={}, 端口={}", protocol, java.util.Arrays.toString(ports));

            } catch (Exception e) {
                running.set(true);
                log.error("协议集群服务器停止失败: 协议={}, 端口={}", protocol, java.util.Arrays.toString(ports), e);
                throw e;
            }
        } else {
            log.warn("协议集群服务器已经停止: 协议={}", protocol);
        }
    }

    @Override
    public String getClusterName() {
        return cluster.getClusterName();
    }

    @Override
    public String getClusterServerInfo() {
        return String.format("ProtocolClusterServer{协议=%s, 端口=%s, 集群=%s, 运行状态=%s}",
                protocol, java.util.Arrays.toString(ports), cluster.getClusterName(), isRunning());
    }

    /**
     * 创建协议服务器实例
     *
     * @return 协议服务器实例
     * @throws Exception 如果创建失败
     */
    private ProtocolServer createProtocolServer(int port) throws Exception {
        try {
            return ProtocolServer.create(protocol, ServerSetting.builder()
                            .port(port)
                            .protocol(protocol)
                            .accessLogFormat(serverSetting.getAccessLogFormat())
                            .allowedMethods(serverSetting.getAllowedMethods())
                            .host(serverSetting.getHost())
                            .backlog(serverSetting.getBacklog())
                            .charset(serverSetting.getCharset())
                            .bossThreads(serverSetting.getBossThreads())
                            .workerThreads(serverSetting.getWorkerThreads())
                            .contextPath(serverSetting.getContextPath())
                            .maxRequestSize(serverSetting.getMaxRequestSize())
                            .maxFileSize(serverSetting.getMaxFileSize())
                            .maxConnections(serverSetting.getMaxConnections())
                    .build());
        } catch (Exception e) {
            log.error("创建协议服务器失败: 协议={}", protocol, e);
            throw e;
        }
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        for (ProtocolServer protocolServer : protocolServers) {
            try {
                if (protocolServer != null && protocolServer.isRunning()) {
                    protocolServer.stop();
                }
            } catch (Exception e) {
                log.warn("清理协议服务器时发生错误", e);
            }
        }

        try {
            if (cluster.isRunning()) {
                cluster.stop();
            }
        } catch (Exception e) {
            log.warn("清理集群时发生错误", e);
        }
    }

    @Override
    public ProtocolServer registerMapping(String path, ServletHandler handler) {
        return null;
    }

    @Override
    public ProtocolServer registerMapping(String path, HttpMethod method, ServletHandler handler) {
        return null;
    }

    @Override
    public ProtocolServer registerMapping(String path, HttpMethod[] methods, ServletHandler handler) {
        return null;
    }
}