package com.chua.common.support.osgi;

import java.util.List;
import java.util.Map;

/**
 * OSGI 启动器接口，负责启动、停止和管理 OSGI 框架实例。
 * <p>
 * OSGI 框架有且只能启动一个全局实例，通过 {@link OsgiLauncherHolder} 持有。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface OsgiLauncher {

    /**
     * 启动 OSGI 框架。
     *
     * @param config 启动配置参数
     */
    void start(Map<String, String> config);

    /**
     * 停止 OSGI 框架。
     */
    void stop();

    /**
     * 判断 OSGI 框架是否已激活。
     *
     * @return 已激活返回 true
     */
    boolean isActive();

    /**
     * 根据类型获取所有已注册的 OSGI 服务实例。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例列表
     */
    <T> List<T> getServices(Class<T> type);

    /**
     * 根据类型获取单个已注册的 OSGI 服务实例。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例，未找到返回 null
     */
    <T> T getService(Class<T> type);

    /**
     * 获取当前已安装的所有 Bundle。
     *
     * @return Bundle 列表
     */
    List<OsgiBundle> getBundles();

    /**
     * 安装指定 URL 的 bundle。
     *
     * @param url bundle 的 jar 包路径或 maven URL
     * @return 已安装的 OsgiBundle
     */
    OsgiBundle installBundle(String url);

    /**
     * 卸载指定符号名称的 bundle。
     *
     * @param bundleSymbolicName bundle 符号名称
     */
    void uninstallBundle(String bundleSymbolicName);
}
