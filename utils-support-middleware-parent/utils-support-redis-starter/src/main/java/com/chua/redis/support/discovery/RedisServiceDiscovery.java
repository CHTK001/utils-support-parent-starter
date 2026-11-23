package com.chua.redis.support.discovery;

import com.chua.common.support.condition.Precondition;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
        // 键一律走 normalizePath（前导斜杠归一）：与读路径 loadFromRedis、注销/更新的 mapKey 保持同一形态，
        // 避免出现「注册落在 discovery:/x、注销/更新却作用在 discovery:x」的分裂键。
        String mapKey = "discovery:" + normalizePath(prefixedPath);
        String entryKey = entryKey(discovery);
        RMapCache<String, String> map = redissonClient.getMapCache(mapKey);
        map.put(entryKey, Json.toJson(discovery), REGISTER_TTL_SECONDS, TimeUnit.SECONDS);
        readCache.remove(normalizePath(prefixedPath));
        // 与 zk/nacos 注册同口径：注册同步落本地视图，且按 serverId 去重更新（心跳周期性重写不累积重复条目），
        // 保证后续 updateService 能按 serverId 命中旧记录并持久化到 Redis，而非只写内存视图。
        localCache.compute(normalizePath(prefixedPath), (k, list) -> {
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
            }
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (discovery.getServerId() != null && discovery.getServerId().equals(list.get(i).getServerId())) {
                    list.set(i, discovery);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                list.add(discovery);
            }
            return list;
        });
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
            Precondition.tryRun(() -> {
                Discovery discovery = Json.fromJson(value, Discovery.class);
                if (discovery != null) {
                    result.add(discovery);
                }
            }).orElse(e -> log.warn("[Discovery] Redis 记录解析失败, path={}, cause={}", path, e.getMessage()));
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
        // 与 registerService/loadFromRedis 同一 normalizePath 形态，保证注销能命中注册时写入的键
        String mapKey = "discovery:" + normalizePath(path);
        RMapCache<String, String> map = redissonClient.getMapCache(mapKey);
        if (discovery == null) {
            readCache.remove(normalizePath(path));
            return;
        }
        String serverId = discovery.getServerId();
        if (StringUtils.isBlank(serverId)) {
            // 无 serverId 时退化为按地址键精确删除（与旧行为一致）
            map.remove(entryKey(discovery));
        } else {
            // 按 serverId 注销该实例的全部地址：实例换址后，调用方持有的旧 Discovery 对象
            // （地址字段已过期）也必须能删掉当前生效的那条记录，否则死地址会留到 TTL 过期；
            // unregisterService(path, serverId) 构建的空地址对象同样依赖本分支才能删中。
            for (Map.Entry<String, String> entry : map.readAllEntrySet()) {
                Discovery cached = null;
                try {
                    cached = Json.fromJson(entry.getValue(), Discovery.class);
                } catch (Exception e) {
                    // 无法解析的历史数据识别不了归属，留给 TTL 自清
                    continue;
                }
                if (cached != null && serverId.equals(cached.getServerId())) {
                    map.remove(entry.getKey());
                }
            }
        }
        readCache.remove(normalizePath(path));
    }

    /**
     * 执行更新
    */
    @Override
    protected void doUpdate(String path, Discovery oldDiscovery, Discovery newDiscovery) {
        // 与 registerService/loadFromRedis 同一 normalizePath 形态，保证更新落在注册时写入的同一张 map
        String mapKey = "discovery:" + normalizePath(path);
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
