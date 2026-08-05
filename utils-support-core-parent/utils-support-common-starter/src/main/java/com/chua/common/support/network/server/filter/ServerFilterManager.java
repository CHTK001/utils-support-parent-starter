package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务器过滤器管理器。
 *
 * <p>统一管理静态过滤器（手动注册）、动态过滤器（SPI 发现）、IOC 过滤器，
 * 按协议类型过滤并按 order 排序后构建 {@link ServerFilterChain}。
 *
 * <h2>过滤器来源优先级</h2>
 * <ol>
 *   <li>静态 — 通过 {@link #addFilter(ServerFilter)} 手动注册</li>
 *   <li>IOC — 从 ObjectContext 扫描</li>
 *   <li>SPI — 通过 ServiceProvider 自动发现</li>
 * </ol>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * ServerFilterManager manager = new ServerFilterManager(ProtocolType.HTTP);
 * manager.addFilter(new CorsFilter());
 *
 * ServerFilterChain chain = manager.buildChain(serverHandler);
 * chain.doFilter(request, response);
 * }</pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@Slf4j
public class ServerFilterManager {

    /**
     * 关联的服务器协议类型，只匹配该协议的 Filter。
     */
    private final ProtocolType protocolType;

    /**
     * 静态过滤器列表，通过 addFilter/removeFilter 手动管理。
     */
    private final List<ServerFilter> staticFilters = new CopyOnWriteArrayList<>();

    /**
     * 合并后的过滤器列表缓存。
     */
    private volatile List<ServerFilter> mergedCache;

    /**
     * 缓存失效标记。
     */
    private volatile boolean dirty = true;

    public ServerFilterManager(ProtocolType protocolType) {
        this.protocolType = protocolType;
    }

    // ==================== 静态过滤器管理 ====================

    /**
     * 添加静态过滤器。
     *
     * @param filter 过滤器
     */
    public void addFilter(ServerFilter filter) {
        if (filter != null && filter.supportProtocol(protocolType)) {
            staticFilters.add(filter);
            dirty = true;
        }
    }

    /**
     * 移除静态过滤器。
     *
     * @param filter 过滤器
     */
    public void removeFilter(ServerFilter filter) {
        if (filter != null) {
            staticFilters.remove(filter);
            dirty = true;
        }
    }

    /**
     * 获取静态过滤器列表的副本。
     *
     * @return 静态过滤器列表
     */
    public List<ServerFilter> getStaticFilters() {
        return new ArrayList<>(staticFilters);
    }

    // ==================== SPI 动态过滤器 ====================

    /**
     * 从 SPI 发现并添加动态过滤器。
     *
     * <p>与 staticFilters 合并，去重。通常由 OSGI reload 触发。
     */
    public void refreshSpiFilters() {
        ServiceProvider.of(ServerFilter.class).forEach((name, filter) -> {
            if (filter.isEnabled() && filter.supportProtocol(protocolType) && !staticFilters.contains(filter)) {
                staticFilters.add(filter);
            }
        });
        dirty = true;
    }

    // ==================== IOC 过滤器 ====================

    /**
     * 从 IOC 容器注入过滤器。
     *
     * @param filters IOC 中的过滤器 Map
     */
    public void addIocFilters(Map<String, ServerFilter> filters) {
        if (filters != null) {
            for (ServerFilter filter : filters.values()) {
                if (filter.isEnabled() && filter.supportProtocol(protocolType) && !staticFilters.contains(filter)) {
                    staticFilters.add(filter);
                }
            }
            dirty = true;
        }
    }

    // ==================== 合并与链构建 ====================

    /**
     * 获取合并后的过滤器列表（静态 + SPI + IOC），按 order 升序排列。
     *
     * <p>结果缓存，仅在 dirty 时重新计算。
     *
     * @return 合并并排序后的过滤器列表
     */
    public List<ServerFilter> getMergedFilters() {
        if (!dirty && mergedCache != null) {
            return mergedCache;
        }
        List<ServerFilter> result = new ArrayList<>(staticFilters);
        result.sort(Comparator.comparingInt(ServerFilter::getOrder));
        mergedCache = result;
        dirty = false;
        return result;
    }

    /**
     * 构建过滤器链。
     *
     * @param handler   最终处理器，链结束时调用
     * @param listeners 过滤器链监听器列表，可为 null
     * @return 过滤器链
     */
    public ServerFilterChain buildChain(ServerHandler handler, List<FilterChainListener> listeners) {
        return new DefaultServerFilterChain(getMergedFilters(), handler, listeners);
    }

    /**
     * 构建过滤器链。
     *
     * @param handler 最终处理器，链结束时调用
     * @return 过滤器链
     */
    public ServerFilterChain buildChain(ServerHandler handler) {
        return new DefaultServerFilterChain(getMergedFilters(), handler, null);
    }

    /**
     * 构建过滤器链（无最终处理器）。
     *
     * @return 过滤器链
     */
    public ServerFilterChain buildChain() {
        return new DefaultServerFilterChain(getMergedFilters(), null, null);
    }

    // ==================== 生命周期 ====================

    /**
     * 初始化所有静态过滤器。
     *
     * @param config 初始化配置
     */
    public void initFilters(ServerFilterConfig config) {
        List<ServerFilter> initialized = new ArrayList<>();
        for (ServerFilter filter : staticFilters) {
            try {
                filter.init(config);
                initialized.add(filter);
            } catch (Exception e) {
                for (ServerFilter initializedFilter : initialized) {
                    try {
                        initializedFilter.destroy();
                    } catch (Exception destroyException) {
                        // 初始化失败时优先保留原始异常，销毁异常不改变失败结果。
                    }
                }
                throw new IllegalStateException("服务器过滤器初始化失败: " + filter.getFilterId(), e);
            }
        }
    }

    /**
     * 销毁所有静态过滤器。
     */
    public void destroyFilters() {
        for (ServerFilter filter : staticFilters) {
            try {
                filter.destroy();
            } catch (Exception e) {
                log.warn("服务器过滤器销毁失败: " + filter.getFilterId() + ", " + e.getMessage());
            }
        }
    }

    // ==================== 响应式过滤器 ====================

    /**
     * 响应式过滤器列表。
     */
    private final List<ReactiveServerFilter> reactiveFilters = new CopyOnWriteArrayList<>();

    /**
     * 添加响应式过滤器。
     *
     * @param filter 响应式过滤器
     */
    public void addReactiveFilter(ReactiveServerFilter filter) {
        if (filter != null && !reactiveFilters.contains(filter)) {
            reactiveFilters.add(filter);
        }
    }

    /**
     * 移除响应式过滤器。
     *
     * @param filter 响应式过滤器
     */
    public void removeReactiveFilter(ReactiveServerFilter filter) {
        if (filter != null) {
            reactiveFilters.remove(filter);
        }
    }

    /**
     * 获取合并后的响应式过滤器列表，按 order 升序排列。
     *
     * @return 合并并排序后的响应式过滤器列表
     */
    public List<ReactiveServerFilter> getMergedReactiveFilters() {
        List<ReactiveServerFilter> result = new ArrayList<>(reactiveFilters);
        result.sort(Comparator.comparingInt(ReactiveServerFilter::getOrder));
        return result;
    }

    // ==================== 清空与替换 ====================

    /**
     * 清空所有过滤器。
     */
    public void clear() {
        staticFilters.clear();
        reactiveFilters.clear();
        mergedCache = null;
        dirty = true;
    }

    /**
     * 替换全部过滤器列表。
     *
     * <p>用于动态更新全部过滤器（如 OSGI 模块安装/卸载后整体替换）。
     *
     * @param filters 新的过滤器列表
     */
    public void setFilters(List<ServerFilter> filters) {
        staticFilters.clear();
        if (filters != null) {
            for (ServerFilter filter : filters) {
                if (filter != null && filter.supportProtocol(protocolType)) {
                    staticFilters.add(filter);
                }
            }
        }
        dirty = true;
    }
}
