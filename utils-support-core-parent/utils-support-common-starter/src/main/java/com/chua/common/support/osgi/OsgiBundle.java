package com.chua.common.support.osgi;

import java.util.List;

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
    * 获取 Bundle 的符号名称。
    *
    * @return 符号名称
    */
    String getSymbolicName();

    /**
    * 获取 Bundle 的版本号。
    *
    * @return 版本号
    */
    String getVersion();

    /**
    * 获取 Bundle 的当前状态。
    *
    * @return 状态字符串（ACTIVE、RESOLVED、INSTALLED 等）
    */
    String getState();

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
    * 注册服务到 OSGI 容器。
    *
    * @param type    服务接口类型
    * @param service 服务实例
    * @param <T>     服务类型
    */
    <T> void registerService(Class<T> type, T service);

    /**
    * 从 OSGI 容器注销服务。
    *
    * @param type    服务接口类型
    * @param service 服务实例
    * @param <T>     服务类型
    */
    <T> void unregisterService(Class<T> type, T service);

    /**
    * 根据类型获取该 Bundle 中已注册的所有服务。
    *
    * @param type 服务接口类型
    * @param <T>  服务类型
    * @return 服务实例列表
    */
    <T> List<T> getServices(Class<T> type);
}
