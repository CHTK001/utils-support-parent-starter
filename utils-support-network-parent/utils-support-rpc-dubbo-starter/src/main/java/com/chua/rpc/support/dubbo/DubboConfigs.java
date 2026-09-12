package com.chua.rpc.support.dubbo;

import org.apache.dubbo.config.ApplicationConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dubbo 全局配置共享容器 — 保证同一应用名下只有一个 {@link ApplicationConfig} 实例。
 *
 * <p>Dubbo 3.x STRICT 配置模式下，同一应用名重复注册不同的
 * {@link ApplicationConfig} 实例会抛出
 * {@code IllegalStateException: Duplicate Configs found for ApplicationConfig}。
 * 服务端与客户端同进程运行时必须共享同一个实例，本工具类按应用名缓存。</p>
 *
 * <p>缓存采用引用计数管理：{@link #get(String)} 获取时计数 +1，
 * {@link #release(String)} 释放时计数 -1，计数归零时移除缓存项。
 * 避免进程内反复启停或跨应用场景下静态缓存泄漏导致配置污染。</p>
 *
 * <p>QoS 配置在首次创建时统一关闭（服务端与客户端行为一致）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class DubboConfigs {

    /**
     * 应用名 → 配置持有者（含引用计数）。
     */
    private static final Map<String, ConfigHolder> HOLDERS = new ConcurrentHashMap<>();

    /**
     * 私有构造器，禁止实例化。
     */
    private DubboConfigs() {
    }

    /**
     * 获取（或创建）指定应用名的共享 {@link ApplicationConfig}，引用计数 +1。
     *
     * @param name 应用名，不允许为 {@code null} 或空串
     * @return 共享实例
     * @throws IllegalArgumentException 应用名为 {@code null} 或空串时抛出（避免下游 {@code setApplication(null)} 静默失效）
     */
    static ApplicationConfig get(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Dubbo application name must not be null or empty");
        }
        while (true) {
            ConfigHolder holder = HOLDERS.get(name);
            if (holder == null) {
                ConfigHolder candidate = new ConfigHolder(create(name));
                ConfigHolder existing = HOLDERS.putIfAbsent(name, candidate);
                if (existing == null) {
                    return candidate.config;
                }
                holder = existing;
            }
            holder.refCount.incrementAndGet();
            return holder.config;
        }
    }

    /**
     * 释放指定应用名的共享 {@link ApplicationConfig}，引用计数 -1。
     *
     * <p>计数归零时移除缓存项，供服务端/客户端关闭时调用，避免静态缓存累积极弱。</p>
     *
     * @param name 应用名，可为 {@code null}（空操作）
     */
    static void release(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        HOLDERS.computeIfPresent(name, (key, holder) -> {
            if (holder.refCount.decrementAndGet() <= 0) {
                return null;
            }
            return holder;
        });
    }

    /**
     * 清空全部缓存（进程级静态缓存，供测试或极端场景强制释放）。
     */
    public static void clear() {
        HOLDERS.clear();
    }

    /**
      * 创建并初始化共享的 {@link ApplicationConfig}（关闭 qos，避免端口冲突）。
     *
     * @param name 应用名
     * @return 初始化后的配置实例
     */
    private static ApplicationConfig create(String name) {
        ApplicationConfig config = new ApplicationConfig();
        config.setName(name);
        config.setQosEnable(false);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("qos.enable", "false");
        config.setParameters(params);
        return config;
    }

    /**
     * 配置持有者：包装 {@link ApplicationConfig} 与引用计数。
     */
    private static final class ConfigHolder {

        /**
         * Dubbo 应用配置实例
         */
        final ApplicationConfig config;

        /**
         * 引用计数
         */
        final AtomicInteger refCount = new AtomicInteger(1);

        ConfigHolder(ApplicationConfig config) {
            this.config = config;
        }
    }
}