package com.chua.common.support.osgi;

import org.jspecify.annotations.NullUnmarked;

/**
 * OSGI Bundle 生命周期监听器接口。
 * <p>
 * 该接口定义了用于监听 OSGi Bundle 各种生命周期事件的回调方法。
 * 实现此接口的类可以接收关于 Bundle 安装、启动、停止、更新、卸载以及状态变更的通知。
 * </p>
 *
 * @author CH
 * @since 2026/07/17
 */
@NullUnmarked
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
}