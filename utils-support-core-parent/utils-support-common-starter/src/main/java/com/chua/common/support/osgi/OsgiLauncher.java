package com.chua.common.support.osgi;

import java.util.List;
import java.util.Map;

/**
 * OSGI 启动器接口，负责启动、停止和管理 OSGI 框架实例。
 * <p>
 * OSGI 框架有且只能启动一个全局实例，通过 {@link OsgiLauncherHolder} 持有。
 * </p>
 * <p>
 * 本接口同时继承 {@link BundleStateQuery}，使任何经由 {@link OsgiLauncherHolder}
 * 取得的启动器引用都能直接进行状态查询与生命周期控制，无需向下转型为具体实现类。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface OsgiLauncher extends BundleStateQuery {

    /**
     * 启动 OSGI 框架。
     * <p>
     * 本方法具备幂等性：框架已处于活动状态时直接返回；
     * 已创建但处于非活动状态（如已 {@code init} 未 {@code start}）时复用同一框架实例。
     * 并发调用由实现方串行化。
     * </p>
     *
     * @param config 启动配置参数
     */
    void start(Map<String, String> config);

    /**
     * 停止 OSGI 框架。
     * <p>
     * 本方法具备幂等性：框架未活动时直接返回。停止过程中会释放所有被持有的服务引用。
     * </p>
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
     * <p>
     * 返回的实例由框架持有服务引用，在 {@link #releaseServices(Class)} 或框架停止前保持有效，
     * 调用方无需（也不应）自行释放。
     * </p>
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例列表，框架未激活时返回空列表
     */
    <T> List<T> getServices(Class<T> type);

    /**
     * 根据类型获取单个已注册的 OSGI 服务实例。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例，未找到返回 空
     */
    <T> T getService(Class<T> type);

    /**
     * 释放本启动器持有的指定类型全部服务引用。
     * <p>
     * 服务引用计数归零后，框架可回收对应服务实例；此后由
     * {@link #getServices(Class)} 取得的实例可能已失效，需重新获取。
     * </p>
     *
     * @param type 服务接口类型
     */
    void releaseServices(Class<?> type);

    /**
     * 释放本启动器持有的全部服务引用。
     */
    void releaseAllServices();

    /**
     * 获取当前已安装的所有 Bundle。
     *
     * @return Bundle 列表
     */
    List<OsgiBundle> getBundles();

    /**
     * 获取服务注册表中已登记的全部服务类型全限定名。
     *
     * @return 服务类型全限定名集合，框架未激活时返回空集合
     */
    List<String> getRegisteredServiceTypes();

    /**
     * 获取服务注册表中已登记的全部服务描述符。
     * <p>
     * 与 {@link #getServices(Class)} 不同，本方法不按类型过滤，
     * 会为每项登记产出恰好一个描述符（含服务属性），
     * 因此适用于枚举整个服务注册表。
     * </p>
     *
     * @return 服务描述符列表，框架未激活时返回空列表
     */
    List<OsgiServiceDescriptor> getServiceDescriptors();

    /**
     * 安装指定 URL 的 bundle。
     *
     * @param url bundle 的 jar 包路径或 Maven URL
     * @return 已安装的 osgibundle
     */
    OsgiBundle installBundle(String url);

    /**
     * 使用指定来源对已安装的 bundle 执行原生热升级。
     * <p>
     * 优先使用原位置更新（传入 {@code null}），升级失败时抛出异常而非静默回退，
     * 以便调用方感知失败并决定是否卸载重装。升级成功后自动重新启动 bundle
     * （若升级前处于活动状态）。
     * </p>
     *
     * @param bundleSymbolicName bundle 符号名称
     * @param url                新版本来源 URL，{@code null} 表示沿用原位置
     * @return 升级后的 osgibundle
     * @throws IllegalArgumentException bundle 不存在时抛出
     */
    OsgiBundle updateBundle(String bundleSymbolicName, String url);

    /**
     * 卸载指定符号名称的 bundle。
     *
     * @param bundleSymbolicName bundle 符号名称
     * @return 存在并成功卸载返回 true，bundle 不存在返回 false
     */
    boolean uninstallBundle(String bundleSymbolicName);
}
