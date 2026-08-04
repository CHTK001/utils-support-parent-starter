package com.chua.common.support.osgi;

import org.jspecify.annotations.NullUnmarked;

/**
 * OSGI Bundle 应用声明接口，允许模块声明自身为 OSGI Bundle 并使用 Bundle 上下文注册服务。
 * <p>
 * 实现类通过 SPI 被 OSGI 启动器发现，在框架启动时回调 {@link #onBundleStart(BundleContext)}，
 * 开发者可在该方法中通过上下文手动注册服务。
 * </p>
 *
 * @author CH
 */
@NullUnmarked
public interface BundleApplication {

    /**
     * OSGI 框架启动时回调，传入 Bundle 上下文供注册服务使用。
     *
     * @param context OSGI Bundle 上下文
     */
    void onBundleStart(BundleContext context);

    /**
     * OSGI 框架停止时回调，用于清理已注册的服务。
     *
     * @param context OSGI Bundle 上下文
     */
    default void onBundleStop(BundleContext context) {}
}
