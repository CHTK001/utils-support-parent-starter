package com.chua.common.support.network.server.filter;

import java.util.Map;

import com.chua.common.support.network.server.ServerSetting;
import org.jspecify.annotations.NullUnmarked;

/**
 * 过滤器初始化配置，在 {@link ServerFilter#init(ServerFilterConfig)} 时传入。
 *
 * <p>
 * 提供 Filter 的初始化参数和所属 Server 的配置信息，
 * Filter 可在此获取自定义配置项。
 *
 * @author CH
 */
@NullUnmarked
public interface ServerFilterConfig {

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
}
