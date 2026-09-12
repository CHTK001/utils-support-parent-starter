package com.chua.rpc.support.dubbo;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.network.rpc.RpcClient;
import com.chua.common.support.network.rpc.RpcConsumerConfig;
import com.chua.common.support.network.rpc.RpcRegistryConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dubbo RPC 客户端实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("dubbo")
@Slf4j
public class DubboRpcClient implements RpcClient {

    /**
      * registry 配置
     */
    private final List<RegistryConfig> registryConfigs = new ArrayList<>();
    /**
      * application 配置
     */
    private final ApplicationConfig applicationConfig;
    /**
      * consumer 配置
     */
    private final ConsumerConfig consumerConfig;
    /**
      * rpc Consumer 配置
     */
    private final RpcConsumerConfig rpcConsumerConfig;
    /**
      * 引用 缓存
     */
    private final Map<Class<?>, ReferenceConfig<?>> referenceCache = new ConcurrentHashMap<>();

    /**
      * 创建 Dubborpc客户端 实例
     * @param rpcRegistryConfigs rpcregistry配置
     * @param consumerCfg rpcconsumer配置
     * @param name 字符串
     * @param consumerCfg consumercfg
     * @param name 名称
     */
    public DubboRpcClient(List<RpcRegistryConfig> rpcRegistryConfigs, RpcConsumerConfig consumerCfg, String name) {
        this.rpcConsumerConfig = consumerCfg;
        applicationConfig = DubboConfigs.get(name);
        for (RpcRegistryConfig cfg : rpcRegistryConfigs) {
            RegistryConfig item = new RegistryConfig();
            item.setAddress(cfg.getAddress());
            item.setProtocol(cfg.getProtocol());
            item.setPort(cfg.getPort());
            item.setUsername(cfg.getUsername());
            item.setPassword(cfg.getPassword());
            if (cfg.getTimeout() != null) { item.setTimeout(cfg.getTimeout()); }
            if (cfg.getSessionTimeout() != null) { item.setSession(cfg.getSessionTimeout()); }
            if (cfg.getFile() != null) { item.setFile(cfg.getFile()); }
            if (cfg.getCheck() != null) { item.setCheck(cfg.getCheck()); }
            if (cfg.getRegister() != null) { item.setRegister(cfg.getRegister()); }
            if (cfg.getSubscribe() != null) { item.setSubscribe(cfg.getSubscribe()); }
            if (cfg.getDynamic() != null) { item.setDynamic(cfg.getDynamic()); }
            if (cfg.getGroup() != null) { item.setGroup(cfg.getGroup()); }
            if (cfg.getVersion() != null) { item.setVersion(cfg.getVersion()); }
            if (cfg.getParameters() != null && !cfg.getParameters().isEmpty()) { item.setParameters(cfg.getParameters()); }
            registryConfigs.add(item);
        }
        this.consumerConfig = buildConsumerConfig(consumerCfg);
    }

    /**
     * 构建consumer配置
     *
     * @param cfg cfg
     * @return 构建consumer配置的结果
     */
    private ConsumerConfig buildConsumerConfig(RpcConsumerConfig cfg) {
        if (cfg == null) {
            return null;
        }
        ConsumerConfig config = new ConsumerConfig();
        if (cfg.getCheck() != null)           { config.setCheck(cfg.getCheck()); }
        if (cfg.getAsync() != null)            { config.setAsync(cfg.getAsync()); }
        if (cfg.getTimeout() != null)          { config.setTimeout(cfg.getTimeout()); }
        if (cfg.getRetries() != null)          { config.setRetries(cfg.getRetries()); }
        if (cfg.getLoadBalance() != null)      { config.setLoadbalance(cfg.getLoadBalance()); }
        if (cfg.getConnections() != null)      { config.setConnections(cfg.getConnections()); }
        if (cfg.getCluster() != null)          { config.setCluster(cfg.getCluster()); }
        if (cfg.getSticky() != null)           { config.setSticky(cfg.getSticky()); }
        if (cfg.getVersion() != null)          { config.setVersion(cfg.getVersion()); }
        if (cfg.getGroup() != null)            { config.setGroup(cfg.getGroup()); }
        return config;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 获取
     *
     * @param targetType Target类型
     * @return 获取的结果
     */
    public <T> T get(Class<T> targetType) {
        ReferenceConfig<T> reference = (ReferenceConfig<T>) referenceCache.computeIfAbsent(targetType, type -> {
            ReferenceConfig<T> ref = new ReferenceConfig<>();
            ref.setApplication(applicationConfig);
            ref.setRegistries(registryConfigs);
            if (consumerConfig != null) {
                ref.setConsumer(consumerConfig);
            }
            ref.setInterface(type);
            if (rpcConsumerConfig != null) {
                if (rpcConsumerConfig.getTimeout() != null)     { ref.setTimeout(rpcConsumerConfig.getTimeout()); }
                if (rpcConsumerConfig.getRetries() != null)     { ref.setRetries(rpcConsumerConfig.getRetries()); }
                if (rpcConsumerConfig.getLoadBalance() != null) { ref.setLoadbalance(rpcConsumerConfig.getLoadBalance()); }
                if (rpcConsumerConfig.getAsync() != null)       { ref.setAsync(rpcConsumerConfig.getAsync()); }
                if (rpcConsumerConfig.getCheck() != null)       { ref.setCheck(rpcConsumerConfig.getCheck()); }
                if (rpcConsumerConfig.getConnections() != null) { ref.setConnections(rpcConsumerConfig.getConnections()); }
                if (rpcConsumerConfig.getCluster() != null)     { ref.setCluster(rpcConsumerConfig.getCluster()); }
                if (rpcConsumerConfig.getSticky() != null)      { ref.setSticky(rpcConsumerConfig.getSticky()); }
                if (rpcConsumerConfig.getVersion() != null)     { ref.setVersion(rpcConsumerConfig.getVersion()); }
                if (rpcConsumerConfig.getGroup() != null)       { ref.setGroup(rpcConsumerConfig.getGroup()); }
            }
            return ref;
        });
        try {
            return reference.get();
        } catch (Exception e) {
            log.error("Failed to get Dubbo service reference: {}", targetType.getName(), e);
            throw new IllegalStateException("Failed to get Dubbo service: " + targetType.getName(), e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        for (ReferenceConfig<?> reference : referenceCache.values()) {
            try {
                reference.destroy();
            } catch (Exception e) {
                log.warn("Failed to destroy Dubbo reference: {}", e.getMessage());
            }
        }
        referenceCache.clear();
        // 释放共享应用配置引用，避免静态缓存跨应用/反复启停泄漏
        DubboConfigs.release(applicationConfig.getName());
        log.info("DubboRpcClient closed");
    }
}