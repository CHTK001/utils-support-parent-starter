package com.chua.rpc.support.sofa;

import com.alipay.sofa.rpc.config.ApplicationConfig;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.alipay.sofa.rpc.config.ServerConfig;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.rpc.RpcProtocolConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import com.chua.common.support.network.rpc.RpcServer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SOFA-RPC 服务端实现。
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("sofa")
@Slf4j
public class SofaRpcServer implements RpcServer {

    private final List<RegistryConfig> registryConfigs = new ArrayList<>();
    private final List<ServerConfig> serverConfigs = new ArrayList<>();
    private final List<ProviderConfig<?>> providerConfigs = new ArrayList<>();
    private final AtomicBoolean state = new AtomicBoolean(false);
    private final ApplicationConfig applicationConfig = new ApplicationConfig();

    public SofaRpcServer(List<RpcRegistryConfig> rpcRegistryConfigs, RpcProtocolConfig protocolConfig, String name) {
        applicationConfig.setAppName(name);
        for (RpcRegistryConfig config : rpcRegistryConfigs) {
            RegistryConfig item = new RegistryConfig();
            if (config.getProtocol() != null) { item.setProtocol(config.getProtocol()); }
            if (config.getAddress() != null)  { item.setAddress(config.getAddress()); }
            if (config.getTimeout() != null)  { item.setTimeout(config.getTimeout()); }
            registryConfigs.add(item);
        }
        initProtocol(protocolConfig);
    }

    private void initProtocol(RpcProtocolConfig config) {
        if (config == null) { return; }
        ServerConfig item = new ServerConfig();
        if (config.name() != null)      { item.setProtocol(config.name()); }
        if (config.port() != null)      { item.setPort(config.port()); }
        if (config.host() != null)      { item.setHost(config.host()); }
        if (config.threads() != null)   { item.setCoreThreads(config.threads()); }
        if (config.accepts() != null)   { item.setAccepts(config.accepts()); }
        if (config.queues() != null)    { item.setQueues(config.queues()); }
        if (config.alive() != null)     { item.setAliveTime(config.alive()); }
        if (config.ioThreads() != null) { item.setIoThreads(config.ioThreads()); }
        if (config.payload() != null)   { item.setPayload(config.payload()); }
        if (config.maxThreads() != null){ item.setMaxThreads(config.maxThreads()); }
        if (config.idleTimeout() != null){ item.setAliveTime(config.idleTimeout()); }
        if (config.keepAlive() != null && config.keepAlive()) { item.setDaemon(false); }
        serverConfigs.add(item);
    }

    @Override
    public void close() {
        state.set(false);
        for (ProviderConfig<?> config : providerConfigs) {
            try {
                config.unExport();
            } catch (Exception e) {
                log.warn("Failed to unExport provider: {}", e.getMessage());
            }
        }
        providerConfigs.clear();
        log.info("SofaRpcServer closed");
    }

    @Override
    public void afterPropertiesSet() {
        state.compareAndSet(false, true);
        log.info("SofaRpcServer initialized");
    }

    @Override
    public RpcServer register(String name, Object bean) {
        ProviderConfig<Object> config = new ProviderConfig<>();
        config.setApplication(applicationConfig);
        config.setInterfaceId(name);
        config.setRef(bean);
        config.setRegistry(registryConfigs);
        config.setServer(serverConfigs);
        config.export();
        providerConfigs.add(config);
        return this;
    }
}