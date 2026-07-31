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
 * @since 4.0.0.41
 */
@Slf4j
@Spi("redis")
public class RedisServiceDiscovery extends AbstractServiceDiscovery {

    private RedissonClient redissonClient;


    public RedisServiceDiscovery(DiscoveryOption discoveryOption) {
        super(discoveryOption);
    }

    public RedisServiceDiscovery(DiscoveryOption discoveryOption, String clusterName) {
        super(discoveryOption, clusterName);
    }

    @Override
    public void start() {
        Config config = new Config();
        config.useSingleServer().setAddress(discoveryOption.getAddress());
        this.redissonClient = Redisson.create(config);
    }

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

    @Override
    protected void doUnregister(String path, Discovery discovery) {
        String mapKey = "discovery:" + path;
        String entryKey = discovery.getHost() + ":" + discovery.getPort();
        RMap<String, String> map = redissonClient.getMap(mapKey);
        map.remove(entryKey);
    }

    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        String mapKey = "discovery:" + path;
        String entryKey = newDiscovery.getHost() + ":" + newDiscovery.getPort();
        RMap<String, String> map = redissonClient.getMap(mapKey);
        map.put(entryKey, Json.toJson(newDiscovery));
    }

    @Override
    public boolean isSupportSubscribe() {
        return true;
    }

    @Override
    public void subscribe(String serviceName, ServiceDiscoveryListener listener) {
        log.warn("RedisServiceDiscovery subscribe is not fully implemented without Pub/Sub");
    }

    @Override
    public void close() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }
}
