package com.chua.common.support.osgi;

import java.util.List;
import java.util.Map;

/**
 * OSGI Bundle 上下文，提供服务注册和获取的能力。
 * <p>
 * 由 OSGI 启动器实现，通过 {@link BundleApplication#onBundleStart(BundleContext)} 回调传给声明方。
 * </p>
 * <p>
 * 本接口不引入任何 OSGI 框架类型，仅暴露纯 JDK 抽象，以便在未接入框架的模块中复用。
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
     * 注册带属性的服务到 OSGI 容器。
     *
     * @param type       服务接口类型
     * @param service    服务实例
     * @param properties 服务属性，可为 {@code null}
     * @param <T>        服务类型
     */
    <T> void registerService(Class<T> type, T service, Map<String, Object> properties);

    /**
     * 注销服务。
     * <p>
     * 仅当容器中注册的实例与 {@code service} 为同一对象时才会真正注销；
     * 仅释放引用计数而保留注册的服务将不做任何处理。
     * </p>
     *
     * @param type    服务接口类型
     * @param service 服务实例
     * @param <T>     服务类型
     * @return 找到并成功注销返回 true
     */
    <T> boolean unregisterService(Class<T> type, T service);

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

    /**
     * 获取服务注册表中已登记的全部服务类型全限定名。
     *
     * @return 服务类型全限定名列表
     */
    List<String> getServiceTypes();

    /**
     * 按符号名称获取已安装的 bundle。
     *
     * @param symbolicName bundle 符号名称
     * @return 对应 bundle，未找到返回 空
     */
    OsgiBundle getBundle(String symbolicName);

    /**
     * 刷新底层框架视图，丢弃本上下文内的缓存快照。
     */
    void refresh();
}
