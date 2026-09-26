package com.chua.common.support.network.server.filter;

import java.util.Map;

import com.chua.common.support.network.server.ServerSetting;

/**
 * 过滤器初始化配置，在 {@link ServerFilter#init(ServerFilterConfig)} 时传入。
 *
 * <p>
 * 提供 Filter 的初始化参数和所属 Server 的配置信息，
 * Filter 可在此获取自定义配置项。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ServerFilterConfig {

    /**
     * 系统属性前缀：配置载体之外以 {@code -Dserver.filter.<参数名>} 兜底传参时使用
     */
    String SYSTEM_PROPERTY_PREFIX = "server.filter.";

    /**
     * 获取过滤器名称。
     *
     * @return 过滤器名称
     */
    String getFilterName();

    /**
     * 获取指定名称的初始化参数值。
     *
     * @param name 参数名
     * @return 参数值，不存在返回 null
     */
    String getInitParameter(String name);

    /**
     * 获取所有初始化参数。
     *
     * @return 参数 Map
     */
    Map<String, String> getInitParameters();

    /**
     * 获取所属服务器的配置。
     *
     * @return 服务器配置
     */
    ServerSetting getServerSetting();

    /**
     * 派生出限定到指定过滤器的配置视图。
     *
     * <p>同一份服务端配置可承载多个过滤器各自的参数，容器在为每个过滤器调用
     * {@link ServerFilter#init(ServerFilterConfig)} 前用本方法把过滤器标识告知配置，
     * 使 {@code 过滤器ID.参数名} 形式的键生效。默认返回自身，即所有过滤器共享同一套参数。</p>
     *
     * @param filterName 过滤器标识，取自 {@link ServerFilter#getFilterId()}
     * @return 限定到该过滤器的配置视图
     */
    default ServerFilterConfig forFilter(String filterName) {
        return this;
    }
}
