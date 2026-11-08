package com.chua.webrtc.model;

import java.util.List;

/**
 * 媒体流轨道（视频 / 音频）。
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface MediaStreamTrack {

    /**
     * 轨道ID。
     *
     * @return 轨道ID
     */
    String getId();

    /**
     * 标签。
     *
     * @return 标签
     */
    String getLabel();

    /**
     * 是否启用的。
     *
     * @return true 表示启用
     */
    boolean isEnabled();

    /**
     * 视频帧事件监听（屏幕流回调）。
     */
    @FunctionalInterface
    interface FrameListener {
        /**
         * 收到视频帧。
         *
         * @param track  轨道
         * @param frame  帧（RGBA/BGRA 原始像素）
         * @param width  宽
         * @param height 高
         * @param pts    时间戳（毫秒）
         */
        void onFrame(MediaStreamTrack track, byte[] frame, int width, int height, long pts);
    }

    /**
     * 添加视频帧监听（仅视频轨道有效）。
     *
     * @param listener 帧监听
     */
    void addFrameListener(FrameListener listener);

    /**
     * 停止轨道。
     */
    void stop();

    /**
     * 获取所有已注册的轨道。
     *
     * @return 轨道列表
     */
    List<MediaStreamTrack> tracks();
}
