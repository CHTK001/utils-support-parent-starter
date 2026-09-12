package com.chua.deeplearning.support.opencv;

import lombok.extern.slf4j.Slf4j;

/**
   * 打开cv 原生库加载工具。
 * <p>
   * 使用 openpnp 打开cv 包内置的 {@code nu.pattern.OpenCV#loadLocally()} 加载本地动态库，
 * 保证 Haar/LBP 级联与图像编解码在 JVM 中可用。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class OpencvNative {

    /**
     * 是否已完成原生库加载。
     */
    private static volatile boolean loaded = false;

    /** 创建 opencvNAT 实例 */
    private OpencvNative() {
    }

    /**
      * 确保 打开cv 原生库已加载。
     * <p>线程安全，可重复调用。</p>
     */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (OpencvNative.class) {
            if (loaded) {
                return;
            }
            try {
                nu.pattern.OpenCV.loadLocally();
                loaded = true;
                log.info("OpenCV 原生库加载成功");
            } catch (Throwable e) {
                throw new IllegalStateException("OpenCV 原生库加载失败", e);
            }
        }
    }
}
