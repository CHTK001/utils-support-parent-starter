package com.chua.redis.support.discovery;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.*;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RMap;

import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.*;

/**
 * Redis 服务发现实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("redis")
public class RedisServiceDiscovery extends AbstractServiceDiscovery {

    /**
     * Redisson客户端
    */
    private RedissonClient redissonClient;


    /**
     * 创建 redis服务discovery 实例
     * @param discoveryOption discovery期权
     */
    public RedisServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    /**
     * 创建 redis服务discovery 实例
     * @param discoveryOption discovery期权
     * @param clusterName 字符串
     */
    public RedisServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    /**
     * 开始
    */
    @Override
    public void start() {
        Config config = new Config();
        config.useSingleServer().setAddress(discoveryOption.getAddress());
        this.redissonClient = Redisson.create(config);
    }

    /**
     * 注册服务
    */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixedPath = addClusterPrefix(path);
        discovery.setUriSpec(prefixedPath);
        String mapKey = "discovery:" + StringUtils.startWithAppend(prefixedPath, "/");
        String entryKey = discovery.getHost() + ":" + discovery.getPort();
        RMap<String, String> map = redissonClient.getMap(mapKey);
        map.put(entryKey, Json.toJson(discovery));
        addToCache(prefixedPath, discovery);
        incrementServiceVersion();
        return this;
    }

    /**
     * 执行注销
    */
    @Override
    protected void doUnregister(String path, Discovery discovery) {
        String mapKey = "discovery:" + path;
        String entryKey = discovery.getHost() + ":" + discovery.getPort();
        RMap<String, String> map = redissonClient.getMap(mapKey);
        map.remove(entryKey);
    }

    /**
     * 执行更新
    */
    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        String mapKey = "discovery:" + path;
        String entryKey = newDiscovery.getHost() + ":" + newDiscovery.getPort();
        RMap<String, String> map = redissonClient.getMap(mapKey);
        map.put(entryKey, Json.toJson(newDiscovery));
    }

    /**
     * 是否支持订阅
    */
    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    /**
     * 订阅
    */
    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        log.warn("RedisServiceDiscovery subscribe is not fully implemented without Pub/Sub");
    }

    /**
     * 关闭
    */
    @Override
    public void close() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }
}
