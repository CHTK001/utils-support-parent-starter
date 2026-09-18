package com.chua.common.support.network.server.dht;

import com.chua.common.support.lang.directory.EventObserver;
import com.chua.common.support.lang.directory.PolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.WatcherEvent;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
* 基于 Kademlia DHT 协议的轮询目录实现。
* <p>
* 通过 DHT 定时查询指定键的值，检测变更后将 创建/删除 事件分发给监听器。
* 支持通过 URL 格式配置 DHT 节点地址、监听键、种子节点等参数。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class DhtPolledDirectory implements PolledDirectory {

    /**
    * 配置键：DHT 节点 URL，格式 {@code dht://host:port/?watch=key1&seed=host2:port2}
    */
    public static final String KEY_DHT_URL = "dht.url";

    /**
    * DHT URL（包含主机、端口、监听键等参数）
    */
    private final String dhtUrl;

    /**
    * DHT 协议引擎
    */
    private DhtProtocol protocol;

    /**
    * 运行状态标志
    */
    private volatile boolean running;

    /**
    * 注册的事件监听器列表
    */
    private final List<PolledListener> listeners = new CopyOnWriteArrayList<>();

    /**
    * 轮询环境配置
    */
    private DirectoryPollerEnvironment environment;

    /**
    * 定时轮询调度器
    */
    private ScheduledExecutorService scheduler;

    /**
    * 定时轮询任务的 期货
    */
    private ScheduledFuture<?> pollingTask;

    /**
    * 上一次轮询的快照（键 -> Discovery 集合）
    */
    private final java.util.Map<String, Set<Discovery>> previousSnapshot = new ConcurrentHashMap<>();

    /**
    * 需要监听变更的键集合
    */
    private final Set<String> watchKeys = ConcurrentHashMap.newKeySet();

    /**
    * 构造 DHT 轮询目录。
    *
    * @param env 轮询环境配置（必须包含 {@link #KEY_DHT_URL}）
    */
    public DhtPolledDirectory(DirectoryPollerEnvironment env) {
        this.dhtUrl = env.getString(KEY_DHT_URL);
        if (dhtUrl == null || dhtUrl.isEmpty()) {
            throw new IllegalArgumentException("'" + KEY_DHT_URL + "' is required");
        }
    }

    @Override
    /** 是否delegatedoperating系统 */
    public boolean isDelegatedOperatingSystem() {
        return false;
    }

    @Override
    /** 开始 */
    public void start(DirectoryPollerEnvironment env, DirectoryPollerExecutor executor) {
        if (running) {
            log.warn("DhtPolledDirectory is already running");
            return;
        }
        this.environment = env;

        try {
            DhtConfig config = parseDhtUrl(dhtUrl);
            KademliaNodeId selfId = KademliaNodeId.random();
            protocol = new DhtProtocol(config, selfId);
            protocol.start();

            if (config.getSeeds() != null) {
                for (String seed : config.getSeeds()) {
                    protocol.addSeed(seed);
                }
            }
            protocol.bootstrap();

            parseWatchKeys(dhtUrl);

            scheduler = ThreadUtils.newScheduledThreadPool(1, "DhtPoller-" + config.getPort());
            long interval = env.getPollingInterval();
            TimeUnit timeUnit = env.getTimeUnit();

            running = true;
            pollingTask = scheduler.scheduleWithFixedDelay(
                    this::pollDirectories, 0, interval, timeUnit);

            log.info("Started DHT directory polling: {} (interval: {} {})",
                    dhtUrl, interval, timeUnit);
        } catch (Exception e) {
            log.error("Failed to start DHT directory polling: {}", dhtUrl, e);
            cleanup();
            throw new RuntimeException("Failed to start DHT directory polling", e);
        }
    }

    @Override
    /** Upgrade */
    public void upgrade() {
        if (running && scheduler != null) {
            scheduler.execute(this::pollDirectories);
        }
    }

    @Override
    /** 添加监听器 */
    public void addListener(PolledListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        cleanup();
        log.info("Stopped DHT directory polling for: {}", dhtUrl);
    }

    /**
    * 轮询所有监听的键，检测变更并分发事件。
    */
    private void pollDirectories() {
        if (!running) {
            return;
        }

        for (String key : watchKeys) {
            try {
                pollDirectory(key);
            } catch (Exception e) {
                log.error("Error polling DHT key: {}", key, e);
            }
        }
    }

    /**
    * 轮询单个 DHT 键，检测变更并分发事件。
    *
    * @param key DHT 键
    */
    private void pollDirectory(String key) {
        String value = protocol.findValue(key);
        Set<Discovery> current = new HashSet<>();
        if (value != null) {
            try {
                Discovery d = Json.fromJson(value, Discovery.class);
                if (d != null) {
                    current.add(d);
                }
            } catch (Exception e) {
                log.debug("Failed to parse Discovery for key {}: {}", key, e.getMessage());
            }
        }

        Set<Discovery> previous = previousSnapshot.getOrDefault(key, Collections.emptySet());
        detectChanges(key, previous, current);
        previousSnapshot.put(key, current);
    }

    /**
    * 对比前后快照，检测新增和删除的 Discovery 并分发事件。
    *
    * @param key      DHT 键
    * @param previous 上一次的快照
    * @param current  当前的快照
    */
    private void detectChanges(String key, Set<Discovery> previous, Set<Discovery> current) {
        Set<String> prevIds = new HashSet<>();
        for (Discovery d : previous) {
            prevIds.add(d.getId());
        }
        Set<String> currIds = new HashSet<>();
        for (Discovery d : current) {
            currIds.add(d.getId());
        }

        for (Discovery d : current) {
            if (!prevIds.contains(d.getId())) {
                notifyEvent(WatcherEvent.CREATE, key, d);
            }
        }
        for (Discovery d : previous) {
            if (!currIds.contains(d.getId())) {
                notifyEvent(WatcherEvent.DELETE, key, d);
            }
        }
    }

    /**
    * 向所有注册的监听器分发事件。
    *
    * @param eventType 事件类型
    * @param key       DHT 键
    * @param discovery 触发事件的 Discovery
    */
    private void notifyEvent(WatcherEvent eventType, String key, Discovery discovery) {
        if (listeners.isEmpty()) {
            return;
        }

        EventObserver observer = EventObserver.builder()
                .currentPath(key)
                .triggerFile(discovery.getId())
                .eventType(eventType)
                .timestamp(LocalDateTime.now())
                .source(discovery.toFullString())
                .build();

        for (PolledListener listener : listeners) {
            try {
                switch (eventType) {
                    case CREATE:
                        listener.onCreate(eventType, observer);
                        break;
                    case MODIFY:
                        listener.onModify(eventType, observer);
                        break;
                    case DELETE:
                        listener.onDelete(eventType, observer);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                log.error("Error notifying {} event for key: {}", eventType, key, e);
            }
        }
    }

    /**
    * 从 URL 中解析需要监听的键（watch 参数）。
    *
    * @param url DHT URL
    */
    private void parseWatchKeys(String url) {
        if (url.contains("?")) {
            String query = url.substring(url.indexOf('?') + 1);
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "watch".equals(kv[0])) {
                    watchKeys.add(kv[1]);
                }
            }
        }
    }

    /**
    * 从 URL 解析 DHT 配置。
    * <p>
    * URL 格式：{@code dht://host:port/?watch=key1&seed=host2:port2&k=8&port=6881}
    * </p>
    *
    * @param url DHT URL
    * @return DhtConfig 实例
    */
    private DhtConfig parseDhtUrl(String url) {
        DhtConfig.DhtConfigBuilder builder = DhtConfig.builder();
        try {
            String stripped = url;
            if (stripped.startsWith("dht://")) {
                stripped = stripped.substring(6);
            }
            String hostPort;
            if (stripped.contains("/")) {
                hostPort = stripped.substring(0, stripped.indexOf('/'));
            } else {
                hostPort = stripped;
            }

            String host = "0.0.0.0";
            int port = 6881;
            if (hostPort.contains(":")) {
                String[] parts = hostPort.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } else if (!hostPort.isEmpty()) {
                host = hostPort;
            }

            builder.host(host).port(port);
            Set<String> seeds = new LinkedHashSet<>();
            if (!host.equals("0.0.0.0") && !host.equals("127.0.0.1")) {
                seeds.add(host + ":" + port);
            }
            if (url.contains("?")) {
                String query = url.substring(url.indexOf('?') + 1);
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "seed".equals(kv[0])) {
                        seeds.add(kv[1]);
                    }
                    if (kv.length == 2 && "k".equals(kv[0])) {
                        builder.kBucketSize(Integer.parseInt(kv[1]));
                    }
                    if (kv.length == 2 && "port".equals(kv[0])) {
                        builder.port(Integer.parseInt(kv[1]));
                    }
                }
            }
            builder.seeds(seeds);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid DHT URL: " + dhtUrl, e);
        }
        return builder.build();
    }

    /**
    * 清理所有资源。
    */
    private void cleanup() {
        if (pollingTask != null && !pollingTask.isCancelled()) {
            pollingTask.cancel(true);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (protocol != null) {
            protocol.close();
        }
        previousSnapshot.clear();
        watchKeys.clear();
    }
}
