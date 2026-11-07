package com.chua.redis.support.discovery;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.*;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RMapCache;

import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.*;
import java.util.concurrent.TimeUnit;

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
     * 本地读缓存有效期（毫秒）。
     * <p>命中期内不访问 Redis，降低远程查询次数；过期后回源一次并回填。</p>
     */
    private static final long READ_CACHE_TTL_MILLIS = 2_000L;

    /**
     * 服务记录在 Redis 中的存活时间（秒）。
     *
     * <p>进程崩溃时没有任何代码会来删记录，只能靠过期自清：调用方按心跳间隔
     * （同步通道为 30 秒）重写记录即可续期，停止心跳后约一个周期记录消失，
     * 不会把死地址长期留在服务表里。</p>
     */
    private static final long REGISTER_TTL_SECONDS = 90L;

    /**
     * 读缓存：Key 为查询路径，Value 为结果快照（含空集合，用于防缓存穿透）
     */
    private final Map<String, CacheEntry> readCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Redisson客户端
    */
    private RedissonClient redissonClient;

    /**
     * 读缓存条目
     *
     * @param values   服务实例快照，允许为空集合
     * @param expireAt 过期时间戳（毫秒）
     */
    private record CacheEntry(Set<Discovery> values, long expireAt) {
        /**
         * 是否已过期
         *
         * @param now 当前时间
         * @return 过期返回 true
         */
        boolean isExpired(long now) {
            return now >= expireAt;
        }
    }


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
     *
     * <p>记录键带上 scatterId：同一服务名下不同业务分组的实例可能使用相同端口，
     * 只用 {@code host:port} 会互相覆盖，最后进来的那个直接把别人的注册挤掉。</p>
     *
     * <p>使用 {@link RMapCache} 并给记录设置存活时间：Redis 没有临时节点语义，
     * 进程崩溃时不会有人来删除记录。调用方按心跳间隔周期性重写即可续期，
     * 停止心跳后记录自然过期，避免读到已经死掉的地址。</p>
     */
    @Override
    public ServiceDiscovery registerService(String path, Discovery discovery) {
        String prefixedPath = addClusterPrefix(path);
        discovery.setUriSpec(prefixedPath);
        String mapKey = "discovery:" + StringUtils.startWithAppend(prefixedPath, "/");
        String entryKey = entryKey(discovery);
        RMapCache<String, String> map = redissonClient.getMapCache(mapKey);
        map.put(entryKey, Json.toJson(discovery), REGISTER_TTL_SECONDS, TimeUnit.SECONDS);
        readCache.remove(normalizePath(prefixedPath));
        incrementServiceVersion();
        return this;
    }

    /**
     * 构造 Redis 记录键。
     *
     * <p>格式为 {@code host:port#scatterId}；scatterId 为空时退化为 {@code host:port}，
     * 与未改造前的行为保持一致。</p>
     *
     * @param discovery 服务实例
     * @return 记录键
     */
    private static String entryKey(Discovery discovery) {
        String base = discovery.getHost() + ":" + discovery.getPort();
        String scatterId = discovery.getScatterId();
        return StringUtils.isBlank(scatterId) ? base : base + "#" + scatterId;
    }

    /**
     * 查询指定路径下的服务实例。
     * <p>命中本地读缓存（2 秒有效）时直接返回；未命中或已过期则回源 Redis，
     * 并把结果（包括空集合）写入缓存，避免对不存在路径的重复查询穿透到 Redis。</p>
     *
     * @param path 服务路径（已带集群前缀）
     * @return 服务实例集合，查不到返回空集合
     */
    @Override
    protected Set<Discovery> get(String path) {
        String cacheKey = normalizePath(path);
        long now = System.currentTimeMillis();
        CacheEntry cached = readCache.get(cacheKey);
        if (cached != null && !cached.isExpired(now)) {
            return cached.values();
        }

        Set<Discovery> remote = loadFromRedis(cacheKey);
        readCache.put(cacheKey, new CacheEntry(remote, now + READ_CACHE_TTL_MILLIS));
        return remote;
    }

    /**
     * 从 Redis 读取指定路径下的服务实例。
     *
     * @param path 归一化后的服务路径
     * @return 服务实例集合，不存在或解析失败返回空集合
     */
    private Set<Discovery> loadFromRedis(String path) {
        if (redissonClient == null) {
            return Collections.emptySet();
        }
        RMapCache<String, String> map = redissonClient.getMapCache("discovery:" + path);
        Collection<String> values = map.readAllValues();
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Discovery> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.isBlank(value)) {
                continue;
            }
            try {
                Discovery discovery = Json.fromJson(value, Discovery.class);
                if (discovery != null) {
                    result.add(discovery);
                }
            } catch (Exception e) {
                log.warn("[Discovery] Redis 记录解析失败, path={}, cause={}", path, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 归一化服务路径：统一前导斜杠，并去掉结尾斜杠。
     *
     * @param path 原始路径
     * @return 归一化后的路径
     */
    private static String normalizePath(String path) {
        String normalized = StringUtils.startWithAppend(path == null ? "/" : path, "/");
        return StringUtils.endWithMove(normalized, "/");
    }

    /**
     * 执行注销
    */
    @Override
    protected void doUnregister(String path, Discovery discovery) {
        String mapKey = "discovery:" + path;
        RMapCache<String, String> map = redissonClient.getMapCache(mapKey);
        map.remove(entryKey(discovery));
        readCache.remove(normalizePath(path));
    }

    /**
     * 执行更新
    */
    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        String mapKey = "discovery:" + path;
        RMapCache<String, String> map = redissonClient.getMapCache(mapKey);
        // 换新地址时清掉旧键，否则旧地址会在过期前一直留在服务表里
        if (oldDiscovery != null) {
            map.remove(entryKey(oldDiscovery));
        }
        map.put(entryKey(newDiscovery), Json.toJson(newDiscovery), REGISTER_TTL_SECONDS, TimeUnit.SECONDS);
        readCache.remove(normalizePath(path));
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
        readCache.clear();
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }
}
