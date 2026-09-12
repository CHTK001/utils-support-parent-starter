package com.chua.common.support.osgi;

import java.util.List;

/**
* OSGI Bundle 上下文，提供服务注册和获取的能力。
* <p>
* 由 OSGI 启动器实现，通过 {@link BundleApplication#onBundleStart(BundleContext)} 回调传给声明方。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface BundleContext {

    /**
    * 注册服务到 OSGI 容器。
    *
    * @param type    服务接口类型
    * @param service 服务实例
    * @param <T>     服务类型
     */
    <T> void registerService(Class<T> type, T service);

    /**
    * 注销服务。
    *
    * @param type    服务接口类型
    * @param service 服务实例
    * @param <T>     服务类型
     */
    <T> void unregisterService(Class<T> type, T service);

    /**
    * 根据类型获取已注册的服务。
    *
    * @param type 服务接口类型
    * @param <T>  服务类型
    * @return 服务实例列表
     */
    <T> List<T> getServices(Class<T> type);

    /**
    * 根据类型获取单个已注册的服务。
    *
    * @param type 服务接口类型
    * @param <T>  服务类型
    * @return 服务实例，未找到返回 空
     */
    <T> T getService(Class<T> type);
}
