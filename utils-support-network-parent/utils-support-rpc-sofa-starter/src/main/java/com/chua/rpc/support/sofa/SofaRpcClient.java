package com.chua.rpc.support.sofa;

import com.alipay.sofa.rpc.config.ApplicationConfig;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SOFA-RPC 客户端实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("sofa")
@Slf4j
public class SofaRpcClient implements RpcClient {

    /**
     * registry Configs
     */
    private final List<RegistryConfig> registryConfigs = new ArrayList<>();
    /**
     * rpc Consumer Config
     */
    private final RpcConsumerConfig rpcConsumerConfig;
    /**
     * application Config
     */
    private final ApplicationConfig applicationConfig = new ApplicationConfig();
    /**
     * consumer Cache
     */
    private final Map<Class<?>, ConsumerConfig<?>> consumerCache = new ConcurrentHashMap<>();

    /**
     * 创建 SofaRpcClient 实例
     * @param rpcRegistryConfigs rpcRegistryConfigs
     * @param RpcConsumerConfig RpcConsumerConfig
     * @param String String
     */
    public SofaRpcClient(List<RpcRegistryConfig> rpcRegistryConfigs, RpcConsumerConfig consumerConfig, String name) {
        this.rpcConsumerConfig = consumerConfig;
        applicationConfig.setAppName(name);
        for (RpcRegistryConfig config : rpcRegistryConfigs) {
            RegistryConfig item = new RegistryConfig();
            if (config.getProtocol() != null) { item.setProtocol(config.getProtocol()); }
            if (config.getAddress() != null)  { item.setAddress(config.getAddress()); }
            if (config.getTimeout() != null)  { item.setTimeout(config.getTimeout()); }
            if ("local".equals(config.getProtocol())) {
                // 与 SofaRpcServer 使用同一注册文件路径（按应用名），保证同机跨进程也能互相发现
                item.setFile(SofaRpcServer.localRegistryFile(name));
            }
            registryConfigs.add(item);
        }
    }



    @Override
    @SuppressWarnings("unchecked")
    /** 获取 */
    public <T> T get(Class<T> targetType) {
        ConsumerConfig<T> config = (ConsumerConfig<T>) consumerCache.computeIfAbsent(targetType, type -> {
            ConsumerConfig<T> c = new ConsumerConfig<>();
            c.setApplication(applicationConfig);
            c.setInterfaceId(type.getName());
            c.setRegistry(registryConfigs);
            if (rpcConsumerConfig != null) {
                if (rpcConsumerConfig.getCheck() != null)       { c.setCheck(rpcConsumerConfig.getCheck()); }
                if (rpcConsumerConfig.getTimeout() != null)      { c.setTimeout(rpcConsumerConfig.getTimeout()); }
                if (rpcConsumerConfig.getRetries() != null)      { c.setRetries(rpcConsumerConfig.getRetries()); }
                if (rpcConsumerConfig.getLoadBalance() != null)  { c.setLoadBalancer(rpcConsumerConfig.getLoadBalance()); }
                if (rpcConsumerConfig.getConnections() != null)  { c.setConnectionNum(rpcConsumerConfig.getConnections()); }
                if (rpcConsumerConfig.getSerialization() != null){ c.setSerialization(rpcConsumerConfig.getSerialization()); }
                if (rpcConsumerConfig.getCluster() != null)      { c.setCluster(rpcConsumerConfig.getCluster()); }
                if (rpcConsumerConfig.getAsync() != null)        { c.setInvokeType(Boolean.TRUE.equals(rpcConsumerConfig.getAsync()) ? "future" : "sync"); }
                if (rpcConsumerConfig.getVersion() != null)      { c.setVersion(rpcConsumerConfig.getVersion()); }
                if (rpcConsumerConfig.getGroup() != null)        { c.setUniqueId(rpcConsumerConfig.getGroup()); }
                if (rpcConsumerConfig.getConnectTimeout() != null){ c.setConnectTimeout(rpcConsumerConfig.getConnectTimeout()); }
                if (Boolean.TRUE.equals(rpcConsumerConfig.getSticky())) { c.setSticky(true); }
            }
            return c;
        });
        return config.refer();
    }

    @Override
    /** 关闭 */
    public void close() throws Exception {
        for (ConsumerConfig<?> config : consumerCache.values()) {
            try {
                config.unRefer();
            } catch (Exception e) {
                log.warn("Failed to unRefer ConsumerConfig: {}", e.getMessage());
            }
        }
        consumerCache.clear();
        log.info("SofaRpcClient closed");
    }
}