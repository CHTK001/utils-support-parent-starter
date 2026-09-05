package com.chua.rpc.support.dubbo;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.rpc.RpcConnectionInfo;
import com.chua.common.support.network.rpc.RpcMetrics;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dubbo RPC 服务端实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("dubbo")
@Slf4j
public class DubboRpcServer implements RpcServer {

    /**
     * registry Configs
     */
    private final List<RegistryConfig> registryConfigs = new ArrayList<>();
    /**
     * 协议 Configs
     */
    private final List<ProtocolConfig> protocolConfigs = new ArrayList<>();
    /**
     * 服务 Configs
     */
    private final List<ServiceConfig<?>> serviceConfigs = new ArrayList<>();
    /**
     * state
     */
    private final AtomicBoolean state = new AtomicBoolean(false);
    /**
     * application Config
     */
    private final ApplicationConfig applicationConfig;

    /**
     * 创建 DubboRpcServer 实例
     * @param rpcRegistryConfigs rpcRegistryConfigs
     * @param RpcProtocolConfig RpcProtocolConfig
     * @param String String
     */
    public DubboRpcServer(List<RpcRegistryConfig> rpcRegistryConfigs, RpcProtocolConfig protocolConfig, String name) {
        applicationConfig = DubboConfigs.get(name);
        initRegistries(rpcRegistryConfigs);
        initProtocol(protocolConfig);
    }

    /** 初始化Registries */
    private void initRegistries(List<RpcRegistryConfig> configs) {
        if (configs == null) {
            return;
        }
        for (RpcRegistryConfig config : configs) {
            RegistryConfig item = new RegistryConfig();
            item.setProtocol(config.getProtocol());
            item.setPort(config.getPort());
            item.setUsername(config.getUsername());
            item.setPassword(config.getPassword());
            item.setTimeout(config.getTimeout());
            if (config.getSessionTimeout() != null) { item.setSession(config.getSessionTimeout()); }
            item.setGroup(config.getGroup());
            item.setVersion(config.getVersion());
            item.setCheck(config.getCheck());
            item.setAddress(config.getAddress());
            if (config.getFile() != null) { item.setFile(config.getFile()); }
            if (config.getRegister() != null) { item.setRegister(config.getRegister()); }
            if (config.getDynamic() != null) { item.setDynamic(config.getDynamic()); }
            if (config.getParameters() != null && !config.getParameters().isEmpty()) { item.setParameters(config.getParameters()); }
            registryConfigs.add(item);
        }
    }

    /** 初始化Protocol */
    private void initProtocol(RpcProtocolConfig config) {
        if (config == null) {
            return;
        }
        ProtocolConfig item = new ProtocolConfig();
        if (config.name() != null)       { item.setName(config.name()); }
        if (config.port() != null)       { item.setPort(config.port()); }
        if (config.host() != null)       { item.setHost(config.host()); }
        if (config.payload() != null)    { item.setPayload(config.payload()); }
        if (config.buffer() != null)     { item.setBuffer(config.buffer()); }
        if (config.threads() != null)    { item.setThreads(config.threads()); }
        if (config.accepts() != null)    { item.setAccepts(config.accepts()); }
        if (config.ioThreads() != null)  { item.setIothreads(config.ioThreads()); }
        if (config.alive() != null)      { item.setAlive(config.alive()); }
        if (config.queues() != null)     { item.setQueues(config.queues()); }
        if (config.serialization() != null) { item.setSerialization(config.serialization()); }
        if (config.codec() != null)      { item.setCodec(config.codec()); }
        if (config.transporter() != null){ item.setTransporter(config.transporter()); }
        if (config.dispatcher() != null) { item.setDispatcher(config.dispatcher()); }
        if (config.threadpool() != null) { item.setThreadpool(config.threadpool()); }
        if (config.heartbeat() != null)  { item.setHeartbeat(config.heartbeat()); }
        if (config.ssl() != null)        { item.setSslEnabled(config.ssl()); }
        if (Boolean.FALSE.equals(config.register())) { item.setRegister(false); }
        if (config.keepAlive() != null)  { item.setKeepAlive(config.keepAlive()); }
        if (config.charset() != null)    { item.setCharset(config.charset()); }
        if (config.coreThreads() != null){ item.setCorethreads(config.coreThreads()); }
        if (config.maxThreads() != null) { item.setThreads(config.maxThreads()); }
        if (config.idleTimeout() != null){ item.setAlive(config.idleTimeout()); }
        protocolConfigs.add(item);
    }

    @Override
    /** 关闭 */
    public void close() {
        state.set(false);
        for (ServiceConfig<?> config : serviceConfigs) {
            try {
                config.unexport();
            } catch (Exception e) {
                log.warn("Failed to unexport service: {}", e.getMessage());
            }
        }
        serviceConfigs.clear();
        // 释放共享应用配置引用，避免静态缓存跨应用/反复启停泄漏
        DubboConfigs.release(applicationConfig.getName());
        log.info("DubboRpcServer closed");
    }

    @Override
    /** AfterProperties设置 */
    public void afterPropertiesSet() {
        state.compareAndSet(false, true);
        log.info("DubboRpcServer initialized");
    }

    @Override
    /** 注册 */
    public RpcServer register(String name, Object bean) {
        ServiceConfig<Object> config = new ServiceConfig<>();
        config.setRegistries(registryConfigs);
        config.setApplication(applicationConfig);
        config.setProtocols(protocolConfigs);
        config.setInterface(name);
        config.setRef(bean);
        config.export();
        serviceConfigs.add(config);
        return this;
    }

    @Override
    /** 获取协议名称 */
    public String getProtocol() {
        return "dubbo";
    }

    @Override
    /** 获取已暴露服务数 */
    public int getServiceCount() {
        try {
            return DubboProtocol.getDubboProtocol().getExporters().size();
        } catch (Exception e) {
            log.warn("Failed to collect Dubbo service count: {}", e.getMessage());
            return serviceConfigs.size();
        }
    }

    @Override
    /** 获取连接信息 */
    public List<RpcConnectionInfo> getConnections() {
        List<RpcConnectionInfo> result = new ArrayList<>();
        try {
            DubboProtocol protocol = DubboProtocol.getDubboProtocol();
            for (ProtocolServer protocolServer : protocol.getServers()) {
                RemotingServer remotingServer = protocolServer.getRemotingServer();
                if (remotingServer == null) {
                    continue;
                }
                InetSocketAddress local = remotingServer.getLocalAddress();
                String localAddress = local != null && local.getAddress() != null
                        ? local.getAddress().getHostAddress() : null;
                int localPort = local != null ? local.getPort() : 0;
                String state = remotingServer.isClosed() ? "CLOSED" : "ACTIVE";
                long now = System.currentTimeMillis();
                for (Channel channel : remotingServer.getChannels()) {
                    InetSocketAddress remote = channel.getRemoteAddress();
                    result.add(new RpcConnectionInfo("dubbo", localAddress, localPort,
                            remote != null && remote.getAddress() != null
                                    ? remote.getAddress().getHostAddress() : null,
                            remote != null ? remote.getPort() : 0,
                            channel.isConnected() ? state : "DISCONNECTED",
                            now, now, Collections.emptyMap()));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to collect Dubbo connections: {}", e.getMessage());
        }
        return result;
    }

    @Override
    /** 获取指标快照 */
    public RpcMetrics getMetrics() {
        RpcMetrics metrics = new RpcMetrics("dubbo");
        List<RpcConnectionInfo> connections = getConnections();
        metrics.setServiceCount(getServiceCount());
        metrics.setTotalConnections(connections.size());
        metrics.setConnections(connections);
        return metrics;
    }
}