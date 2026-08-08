package com.chua.rpc.support.dubbo;

import org.apache.dubbo.config.ApplicationConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dubbo 全局配置共享容器 — 保证同一应用名下只有一个 {@link ApplicationConfig} 实例。
 *
 * <p>Dubbo 3.x STRICT 配置模式下，同一应用名重复注册不同的
 * {@link ApplicationConfig} 实例会抛出
 * {@code IllegalStateException: Duplicate Configs found for ApplicationConfig}。
 * 服务端与客户端同进程运行时必须共享同一个实例，本工具类按应用名缓存。</p>
 *
 * <p>QoS 配置在首次创建时统一关闭（服务端与客户端行为一致）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class DubboConfigs {

    /**
     * 应用名 → 共享 {@link ApplicationConfig} 缓存。
     */
    private static final Map<String, ApplicationConfig> APPLICATIONS = new ConcurrentHashMap<>();

    /**
     * 私有构造器，禁止实例化。
     */
    private DubboConfigs() {
    }

    /**
     * 获取（或创建）指定应用名的共享 {@link ApplicationConfig}。
     *
     * @param name 应用名，不允许为 {@code null} 或空串
     * @return 共享实例
     * @throws IllegalArgumentException 应用名为 {@code null} 或空串时抛出（避免下游 {@code setApplication(null)} 静默失效）
     */
    static ApplicationConfig application(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Dubbo application name must not be null or empty");
        }
        return APPLICATIONS.computeIfAbsent(name, DubboConfigs::create);
    }

    /**
     * 清空全部缓存（进程级静态缓存，供应用关闭或反复启停测试时释放）。
     */
    public static void clear() {
        APPLICATIONS.clear();
    }

    /**
     * 创建并初始化共享的 {@link ApplicationConfig}（关闭 QoS，避免端口冲突）。
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
}
