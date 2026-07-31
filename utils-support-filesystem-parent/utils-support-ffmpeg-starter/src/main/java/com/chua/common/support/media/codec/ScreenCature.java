package com.chua.common.support.media.codec;

import java.awt.image.BufferedImage;

/**
 * 屏幕采集接口 — 提供帧采集能力。
 *
 * <p>实现类应通过 {@code @Spi("name")} 注解注册，由 ServiceProvider 发现。</p>
 * <p>返回 {@code BufferedImage} 以保证广泛兼容性。子接口或特定实现可在需要时暴露零拷贝 AVPicture 访问。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ScreenCature extends AutoCloseable {

    /**
     * 初始化采集器。
     *
     * @param width 采集宽度（全屏或按需）
     * @param height 采集高度
     * @param fps 目标帧率
     * @return 初始化成功返回 true
     */
    boolean init(int width, int height, int fps);

    /**
     * 采集一帧图像，格式为 BufferedImage.TYPE_3BYTE_BGR。
     *
     * @return BGR 字节序的 BufferedImage，失败返回 null
     */
    java.awt.image.BufferedImage grabFrame();

    /**
     * 获取采集宽度。
     *
     * @return 采集宽度
     */
    int getWidth();

    /**
     * 获取采集高度。
     *
     * @return 采集高度
     */
    int getHeight();

    /**
     * 释放资源。
     */
    @Override
    void close();
}
