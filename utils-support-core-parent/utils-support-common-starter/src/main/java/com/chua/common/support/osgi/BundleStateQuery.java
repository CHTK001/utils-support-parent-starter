package com.chua.common.support.osgi;

import java.util.List;
import java.util.Map;

/**
 * OSGI 框架状态查询接口。
 *
 * @author CH
 * @since 2026/07/17
 */
public interface BundleStateQuery {

    /**
     * 根据符号名称获取特定的 OSGi 捆绑包。
     *
     * @param symbolicName 捆绑包的符号名称
     * @return 对应的 OSGi 捆绑包对象，如果未找到则返回 null
     */
    OsgiBundle getBundle(String symbolicName);

    /**
     * 根据指定状态获取所有匹配的 OSGi 捆绑包列表。
     *
     * @param state 要查询的捆绑包状态字符串
     * @return 匹配状态的 OSGi 捆绑包列表
     */
    List<OsgiBundle> getBundlesByState(String state);

    /**
     * 获取当前处于活动（ACTIVE）状态的 OSGi 捆绑包列表。
     *
     * @return 活动状态的 OSGi 捆绑包列表
     */
    List<OsgiBundle> getActiveBundles();

    /**
     * 获取系统中已安装的 OSGi 捆绑包总数。
     *
     * @return 捆绑包的总数量
     */
    long getBundleCount();

    /**
     * 获取当前 OSGi 框架的统计信息。
     *
     * @return 包含框架统计数据的键值对映射
     */
    Map<String, Object> getFrameworkStats();

    /**
     * 检查是否已安装并配置为自动启动的捆绑包功能。
     *
     * @return 如果启用了自动启动已安装捆绑包则返回 true，否则返回 false
     */
    boolean isAutoStartInstalledBundles();

    /**
     * 设置是否允许自动启动已安装的捆绑包。
     *
     * @param autoStart 如果设置为 true，则启用自动启动已安装捆绑包
     */
    void setAutoStartInstalledBundles(boolean autoStart);
}