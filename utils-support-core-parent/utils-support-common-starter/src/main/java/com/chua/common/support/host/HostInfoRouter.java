package com.chua.common.support.host;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机不可变信息采集路由器：按优先级选出当前平台上最合适的
 * {@link HostInfoProvider} 实现。
 *
 * <p>路由规则：
 * <ol>
 *   <li>只考虑 {@link HostInfoProvider#supports()} 返回 true 的实现；</li>
 *   <li>按 {@link HostInfoProvider#priority()} 降序尝试；</li>
 *   <li>首个取到<b>至少一个一级锚点</b>的实现即胜出——
 *       只拿到主机名/MAC 的实现视为不可用，否则会导致指纹退化成弱识别；</li>
 *   <li>全部失败时返回空快照，让上层走降级路径，而不是抛异常打断调用方。</li>
 * </ol>
 *
 * <p>结果按通道缓存一次，进程内默认只采集一次；需要重新采集时调用
 * {@link #invalidate()}（例如换了网卡后要求刷新）。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public final class HostInfoRouter {

    /**
     * 缓存：不适用任何回收策略，进程内单例
     */
    private static volatile HostFacts cache;

    /**
     * 缓存对应的通道名，便于诊断
     */
    private static volatile String cacheChannel = "";

    /**
     * 私有构造，禁止实例化
     */
    private HostInfoRouter() {
    }

    /**
     * 获取当前主机不可变信息（带缓存）。
     *
     * @return 快照，永不为 null
     */
    public static HostFacts current() {
        HostFacts local = cache;
        if (local != null) {
            return local;
        }
        synchronized (HostInfoRouter.class) {
            if (cache == null) {
                cache = capture();
            }
            return cache;
        }
    }

    /**
     * 强制重新采集并刷新缓存。
     *
     * @return 新采集的快照
     */
    public static HostFacts refresh() {
        synchronized (HostInfoRouter.class) {
            cache = capture();
            return cache;
        }
    }

    /**
     * 清空缓存，下次调用 {@link #current()} 时重新采集。
     */
    public static void invalidate() {
        synchronized (HostInfoRouter.class) {
            cache = null;
            cacheChannel = "";
        }
    }

    /**
     * 显式指定当前主机信息，用于测试或容器化部署时固定指纹源。
     *
     * @param facts 快照，传 null 等于清空缓存
     */
    public static void override(HostFacts facts) {
        synchronized (HostInfoRouter.class) {
            cache = facts;
            cacheChannel = facts == null ? "" : "override:" + facts.channel();
        }
    }

    /**
     * 获取当前缓存来自哪个通道。
     *
     * @return 通道名；未采集时为空串
     */
    public static String channel() {
        return cacheChannel;
    }

    /**
     * 实际执行一次采集。
     *
     * @return 快照
     */
    private static HostFacts capture() {
        List<HostInfoProvider> providers = candidates();
        if (providers.isEmpty()) {
            log.debug("[host] 未发现任何 HostInfoProvider 实现，主机指纹将退化为 JVM 属性");
            cacheChannel = "";
            return HostFacts.empty();
        }
        for (HostInfoProvider provider : providers) {
            try {
                HostFacts facts = provider.capture();
                if (facts != null && facts.strongCount() > 0) {
                    cacheChannel = StringUtils.isBlank(provider.name()) ? provider.getClass().getSimpleName() : provider.name();
                    log.debug("[host] 主机信息采集通道={}，一级锚点={}", cacheChannel, facts.strongCount());
                    return facts;
                }
                log.debug("[host] 通道 {} 未取到一级锚点，尝试下一个", provider.getClass().getSimpleName());
            } catch (Exception e) {
                log.debug("[host] 通道 {} 采集失败: {}", provider.getClass().getSimpleName(), e.getMessage());
            }
        }
        cacheChannel = "";
        return HostFacts.empty();
    }

    /**
     * 收集当前平台可用的采集实现，按优先级降序。
     *
     * @return 候选实现列表，可能为空
     */
    private static List<HostInfoProvider> candidates() {
        List<HostInfoProvider> all;
        try {
            all = ServiceProvider.of(HostInfoProvider.class).collect();
        } catch (Exception e) {
            log.debug("[host] SPI 发现 HostInfoProvider 失败: {}", e.getMessage());
            return List.of();
        }
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<HostInfoProvider> list = new ArrayList<>(all.size());
        for (HostInfoProvider provider : all) {
            if (provider == null) {
                continue;
            }
            try {
                if (provider.supports()) {
                    list.add(provider);
                }
            } catch (Exception e) {
                log.debug("[host] 通道 {} 的 supports() 异常: {}",
                        provider.getClass().getSimpleName(), e.getMessage());
            }
        }
        // 优先级降序；同优先级保持 SPI 的注册顺序
        list.sort((a, b) -> Integer.compare(safePriority(b), safePriority(a)));
        return list;
    }

    /**
     * 取优先级，异常按 0 处理。
     *
     * @param provider 采集实现
     * @return 优先级
     */
    private static int safePriority(HostInfoProvider provider) {
        try {
            return provider.priority();
        } catch (Exception e) {
            return 0;
        }
    }
}
