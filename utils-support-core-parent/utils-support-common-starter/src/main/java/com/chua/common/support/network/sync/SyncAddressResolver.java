package com.chua.common.support.network.sync;

import com.chua.common.support.network.client.ClientSetting;
import com.chua.common.support.network.discovery.Discovery;
import com.chua.common.support.network.discovery.ServiceDiscovery;
import com.chua.common.support.utils.StringUtils;

/**
 * 同步通道地址解析器。
 *
 * <p>把「地址从哪来」收敛成一条规则链，供各 {@link SyncClient} 实现共用：</p>
 * <ol>
 *     <li>配置了服务发现：按 {@code serviceName + scatterId} 查询节点，取第一个可用地址；</li>
 *     <li>发现不到：回落到显式地址 {@code serverUrl}；</li>
 *     <li>都没有：使用默认值。</li>
 * </ol>
 *
 * <p>{@code scatterId} 即 {@link Discovery#getScatterId()}，用于在同一服务名下区分不同业务集群，
 * 避免多个 Client/Server 共用一套发现服务时取到别人的地址。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SyncAddressResolver {

    /**
     * 默认协议
     */
    private static final String DEFAULT_PROTOCOL = "http";

    /**
     * 工具类不允许实例化
     */
    private SyncAddressResolver() {
    }

    /**
     * 把 {@code SyncProtocol#createClient(Object)} 的入参归一化为显式地址。
     *
     * <p>入参有两种合法形态：</p>
     * <ul>
     *     <li>{@link SyncSetting}：按其中的服务发现信息解析，解析不到再用它的 serverUrl；</li>
     *     <li>String：直接作为地址。</li>
     * </ul>
     *
     * <p>不识别 {@code SyncSetting} 的实现会把入参当成 String 而静默回落到默认地址，
     * 因此所有 {@code SyncProtocol} 实现都必须经由本方法取值，不要各自 {@code instanceof String} 判断。</p>
     *
     * @param setting 协议工厂收到的原始入参
     * @param defaultUrl 无法解析时的默认地址
     * @return 可用的地址；始终非空
     */
    public static String resolveAddress(Object setting, String defaultUrl) {
        if (setting instanceof SyncSetting syncSetting) {
            String resolved = resolve(syncSetting);
            return StringUtils.isNotBlank(resolved) ? resolved : defaultUrl;
        }
        if (setting instanceof String url && StringUtils.isNotBlank(url)) {
            return url;
        }
        return defaultUrl;
    }

    /**
     * 解析同步服务端地址（{@link SyncSetting} 版本）。
     *
     * <p>规则：服务发现可用且解析到节点 → 用节点地址；否则 → 用 {@link SyncSetting#getServerUrl()}。</p>
     *
     * @param setting 同步通道配置
     * @return 解析出的地址；全部为空时返回 null
     */
    public static String resolve(SyncSetting setting) {
        if (setting == null) {
            return null;
        }
        return resolve(setting.getDiscovery(),
                setting.getServiceName(),
                setting.effectiveScatterId(),
                setting.getServerUrl());
    }

    /**
     * 解析同步服务端地址。
     *
     * @param discovery  服务发现实例，可为 null
     * @param serviceName 服务名（即注册时使用的 path），可为 null
     * @param scatterId  业务分组标识，可为 null（不过滤）
     * @param fallback   发现不可用时的兜底地址，可为 null
     * @return 解析出的地址；全部失败时返回兜底值（可能为 null）
     */
    public static String resolve(ServiceDiscovery discovery, String serviceName, String scatterId, String fallback) {
        String discovered = resolveFromDiscovery(discovery, serviceName, scatterId);
        if (StringUtils.isNotBlank(discovered)) {
            return discovered;
        }
        return fallback;
    }

    /**
     * 仅从服务发现解析地址。
     *
     * @param discovery   服务发现实例
     * @param serviceName 服务名
     * @param scatterId   业务分组标识
     * @return 服务地址（协议://主机:端口）；解析不到返回 null
     */
    public static String resolveFromDiscovery(ServiceDiscovery discovery, String serviceName, String scatterId) {
        if (discovery == null || StringUtils.isBlank(serviceName)) {
            return null;
        }
        try {
            Discovery node = discovery.getService(serviceName, scatterId, "weight", DEFAULT_PROTOCOL);
            return toAddress(node);
        } catch (Exception e) {
            // 发现服务不可用时不能让同步通道启动失败，交给调用方回落到显式地址
            return null;
        }
    }

    /**
     * 把服务实例转换为可访问地址。
     *
     * @param node 服务实例
     * @return 地址字符串；node 为空或缺少主机时返回 null
     */
    public static String toAddress(Discovery node) {
        if (node == null || StringUtils.isBlank(node.getHost())) {
            return null;
        }
        String protocol = StringUtils.isBlank(node.getProtocol()) ? DEFAULT_PROTOCOL : node.getProtocol();
        String host = node.getHost();
        int port = node.getPort();
        if (port <= 0) {
            return protocol + "://" + host;
        }
        return protocol + "://" + host + ":" + port;
    }
}
