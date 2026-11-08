package com.chua.webrtc.spi.media;

import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * 桌面采集 SPI。
 *
 * <p>负责在 Java 服务端采集本地桌面/窗口，并作为视频流推送给 WebRTC 点对点连接。</p>
 *
 * <p>实现类通过 {@code webrtc-java} 的 {@code VideoDesktopSource} 完成系统级录屏：
 * <ul>
 *   <li>Windows: DXGI Desktop Duplication (跳过 AWT Robot 软件截屏)</li>
 *   <li>Linux: X11 / PipeWire</li>
 *   <li>macOS: VideoToolbox</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface ScreenCaptureService {

    /**
     * SPI 名称。
     *
     * @return 服务名称
     */
    String spiName();

    /**
     * 启动屏幕采集，开始向关联的视频轨道推送帧。
     *
     * @param track 视频轨道
     */
    void start(VideoTrack track);

    /**
     * 停止屏幕采集。
     *
     * @param track 视频轨道
     */
    void stop(VideoTrack track);

    /**
     * 设置屏幕采集刷新率。
     *
     * @param fps 帧率（1-30）
     */
    void setCaptureRate(int fps);

    /**
     * 设置视频规模（分辨率）
     *
     * @param width 宽
     * @param height 高
     */
    void setResize(int width, int height);

    /**
     * 是否支持 Window 模式（单个窗口而非全屏）
     *
     * @return true 表示支持窗口模式
     */
    boolean supportWindowMode();

    /**
     * 列出可采集的屏幕。
     *
     * @return 屏幕列表
     */
    ScreenInfo[] screens();

    /**
     * 关闭采集器，释放 Native 资源。
     */
    void close();

    /**
     * 获取当前采集的视频轨道（由 PeerConnectionFactory 与采集源绑定创建）
     *
     * @return 视频轨道，采集未开始时为 null
     */
    VideoTrack videoTrack();

    /**
     * 屏幕信息。
     */
    interface ScreenInfo {
        /**
         * 屏幕ID。
         */
        String id();

        /**
         * 屏幕名称。
         */
        String name();

        /**
         * 宽度。
         */
        int width();

        /**
         * 高度。
         */
        int height();
    }
}
