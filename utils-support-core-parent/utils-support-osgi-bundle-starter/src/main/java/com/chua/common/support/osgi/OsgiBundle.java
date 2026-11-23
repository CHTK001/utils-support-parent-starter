package com.chua.common.support.osgi;

import java.util.List;
import java.util.Map;

/**
 * OSGI Bundle 接口，表示 OSGI 框架中的一个模块单元。
 * <p>
 * Bundle 是客户端操作 OSGI 的主要入口，可注册/注销服务、获取服务、管理自身生命周期。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface OsgiBundle {

    /**
     * 获取 Bundle 在框架内的唯一编号。
     *
     * @return bundle 编号
     */
    long getBundleId();

    /**
     * 获取 Bundle 的符号名称。
     *
     * @return 符号名称，未声明时返回 空
     */
    String getSymbolicName();

    /**
     * 获取 Bundle 的版本号。
     *
     * @return 版本号，未声明时返回 空
     */
    String getVersion();

    /**
     * 获取 Bundle 的来源位置。
     *
     * @return 位置 URL，未知时返回 空
     */
    String getLocation();

    /**
     * 获取 Bundle 的 MANIFEST 头部声明。
     *
     * @return 头部键值对，只读视图
     */
    Map<String, String> getHeaders();

    /**
     * 获取 Bundle 的当前状态。
     *
     * @return 状态字符串（ACTIVE、RESOLVED、INSTALLED 等）
     */
    String getState();

    /**
     * 判断 Bundle 当前是否处于活动状态。
     *
     * @return 活动状态返回 true
     */
    default boolean isActive() {
        return "ACTIVE".equals(getState());
    }

    /**
     * 启动该 Bundle。
     */
    void start();

    /**
     * 停止该 Bundle。
     */
    void stop();

    /**
     * 卸载该 Bundle（主动基于 bundle 对象卸载，不依赖 symbolic名称 字符串匹配）。
     * <p>卸载前若处于 ACTIVE/STARTING 状态会先停止；卸载后 bundle 进入 UNINSTALLED 状态。</p>
     */
    void uninstall();

    /**
     * 使用指定来源对该 Bundle 执行原生热升级。
     * <p>
     * 沿用原位置更新时传入 {@code null}。若该 Bundle 在升级前处于活动状态，
     * 升级成功后自动重新启动。
     * </p>
     *
     * @param url 新版本来源 URL，{@code null} 表示沿用原位置
     */
    void update(String url);

    /**
     * 注册服务到 OSGI 容器。
     *
     * @param type    服务接口类型
     * @param service 服务实例
     * @param <T>     服务类型
     */
    <T> void registerService(Class<T> type, T service);

    /**
     * 注册带属性的服务到 OSGI 容器。
     *
     * @param type       服务接口类型
     * @param service    服务实例
     * @param properties 服务属性，可为 {@code null}
     * @param <T>        服务类型
     */
    <T> void registerService(Class<T> type, T service, Map<String, Object> properties);

    /**
     * 从 OSGI 容器注销服务。
     * <p>
     * 仅当容器中注册的实例与 {@code service} 为同一对象时才会注销，
     * 避免传入错误实例导致同名服务被误注销。
     * </p>
     *
     * @param type    服务接口类型
     * @param service 服务实例
     * @param <T>     服务类型
     * @return 找到并成功注销返回 true
     */
    <T> boolean unregisterService(Class<T> type, T service);

    /**
     * 根据类型获取该 Bundle 中已注册的所有服务。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例列表
     */
    <T> List<T> getServices(Class<T> type);

    /**
     * 获取本 Bundle 已注册的服务类型全限定名。
     *
     * @return 服务类型全限定名列表
     */
    List<String> getRegisteredServiceTypes();
}
