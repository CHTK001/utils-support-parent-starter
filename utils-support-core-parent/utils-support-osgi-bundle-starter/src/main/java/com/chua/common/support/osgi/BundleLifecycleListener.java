package com.chua.common.support.osgi;


/**
 * OSGI Bundle 生命周期监听器接口。
 * <p>
 * 该接口定义了用于监听 osgi Bundle 各种生命周期事件的回调方法。
 * 实现此接口的类可以接收关于 Bundle 安装、启动、停止、更新、卸载以及状态变更的通知。
 * </p>
 * <p>
 * 所有回调均由启动器内部注册的框架级 {@code BundleListener} 驱动，
 * 因此外部直接调用框架 API 所引发的状态变化同样会被感知。
 * 单个监听器抛出异常不会阻断其他监听器的回调。
 * </p>
 *
 * @author CH
 * @since 2026/07/17
 */
public interface BundleLifecycleListener {

    /**
     * 当 Bundle 被安装时调用此方法。
     *
     * @param symbolicName 被安装的 Bundle 的符号名称。
     */
    void onBundleInstalled(String symbolicName);

    /**
     * 当 Bundle 被启动时调用此方法。
     *
     * @param symbolicName 被启动的 Bundle 的符号名称。
     */
    void onBundleStarted(String symbolicName);

    /**
     * 当 Bundle 被停止时调用此方法。
     *
     * @param symbolicName 被停止的 Bundle 的符号名称。
     */
    void onBundleStopped(String symbolicName);

    /**
     * 当 Bundle 被更新时调用此方法。
     *
     * @param symbolicName 被更新的 Bundle 的符号名称。
     * @param newVersion   更新后的 Bundle 版本号。
     */
    void onBundleUpdated(String symbolicName, String newVersion);

    /**
     * 当 Bundle 被卸载时调用此方法。
     *
     * @param symbolicName 被卸载的 Bundle 的符号名称。
     */
    void onBundleUninstalled(String symbolicName);

    /**
     * 当 Bundle 的状态发生变化时调用此方法。
     *
     * @param symbolicName Bundle 的符号名称。
     * @param oldState     变化前的 Bundle 状态字符串。
     * @param newState     变化后的 Bundle 状态字符串。
     */
    void onBundleStateChanged(String symbolicName, String oldState, String newState);

    /**
     * 当 Bundle 状态变为 {@code UNINSTALLED} 时调用此方法。
     * <p>
     * 该回调是 {@link #onBundleStateChanged(String, String, String)} 的语义细化：
     * 框架在进入 {@code UNINSTALLED} 后其位置记录即被清除，
     * 默认实现据此触发 {@link #onBundleUninstalled(String)}，避免监听器漏收卸载事件。
     * </p>
     *
     * @param symbolicName 被卸载的 Bundle 的符号名称。
     * @param location     卸载前的来源位置，可为 {@code null}
     * @param version      卸载前的版本号，可为 {@code null}
     */
    default void onBundleRemoved(String symbolicName, String location, String version) {
        onBundleUninstalled(symbolicName);
    }

    /**
     * 当 Bundle 因依赖无法满足等原因进入 {@code RESOLVED} 失败态时调用此方法。
     *
     * @param symbolicName 解析失败的 Bundle 的符号名称。
     * @param reason       框架给出的失败原因，可为 {@code null}
     */
    default void onBundleResolveFailed(String symbolicName, String reason) {
        // 默认无动作
    }
}
