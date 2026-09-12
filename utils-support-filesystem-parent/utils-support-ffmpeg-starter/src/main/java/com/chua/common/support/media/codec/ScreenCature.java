package com.chua.common.support.media.codec;

import org.bytedeco.javacv.Frame;

/**
* 屏幕采集接口 — 提供帧采集能力。
*
* <p>实现类应通过 {@code @Spi("name")} 注解注册，由 ServiceProvider 发现。</p>
* <p>返回 {@link Frame}，YUV420P 格式，零拷贝传递给编码器。</p>
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
    * 采集一帧图像，YUV420P 格式的 帧。
    * <p>Frame 的 image[0]=Y, image[1]=U, image[2]=V 为 DirectByteBuffer，零拷贝。</p>
    *
    * @return YUV420P 格式的 帧，失败返回 空
     */
    Frame grabFrame();

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
