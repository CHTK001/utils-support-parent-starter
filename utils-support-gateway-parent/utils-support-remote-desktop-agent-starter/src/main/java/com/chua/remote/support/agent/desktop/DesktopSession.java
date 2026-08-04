package com.chua.remote.support.agent.desktop;

import com.chua.common.support.media.codec.VideoEncoder;
import org.bytedeco.javacv.Frame;

/**
 * 桌面会话 SPI 抽象。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DesktopSession {

    /**
     * 画质模式枚举。
     */
    enum QualityMode {
        /**
         * 优先速度
         */
        SPEED,
        /**
         * 均衡模式
         */
        BALANCED,
        /**
         * 优先画质
         */
        QUALITY,
        /**
         * 原始画质
         */
        ORIGINAL
    }

    /**
     * 编码后的屏幕帧记录。
     *
     * @param width 帧宽度
     * @param height 帧高度
     * @param keyFrame 是否为关键帧
     * @param data 编码后的数据
     */
    record EncodedScreen(int width, int height, boolean keyFrame, byte[] data) {
    }

    /**
     * 启动会话采集。
     */
    void start();

    /**
     * 停止会话采集。
     */
    void stop();

    /**
     * 送入 YUV420P Frame 进行编码，零拷贝。
     *
     * @param frame YUV420P 格式的 Frame
     */
    void feedFrame(Frame frame);

    /**
     * 设置目标尺寸。
     *
     * @param width 目标宽度
     * @param height 目标高度
     */
    void setTargetSize(int width, int height);

    /**
     * 设置画质模式。
     *
     * @param mode 画质模式
     */
    void setQualityMode(QualityMode mode);

    /**
     * 设置画质值。
     *
     * @param quality 画质值
     */
    void setQuality(int quality);

    /**
     * 获取当前画质模式。
     *
     * @return 画质模式
     */
    QualityMode getQualityMode();

    /**
     * 会话是否正在运行。
     *
     * @return true 表示正在运行
     */
    boolean isRunning();

    /**
     * 获取目标宽度。
     *
     * @return 目标宽度
     */
    int getTargetWidth();

    /**
     * 获取目标高度。
     *
     * @return 目标高度
     */
    int getTargetHeight();

    /**
     * 获取视频编码器。
     *
     * @return VideoEncoder 实例
     */
    VideoEncoder getEncoder();

    /**
     * 获取会话ID。
     *
     * @return 会话ID
     */
    String getSessionId();

    /**
     * 获取当前FPS并重置计数器。
     *
     * @return FPS 值
     */
    int getFps();
}
