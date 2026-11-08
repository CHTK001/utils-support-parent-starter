package com.chua.webrtc.model;

import java.util.List;

/**
 * 视频流轨道监听。
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface MediaStreamTrackListener {

    /**
     * 远端视频轨道开始。
     *
     * @param track 视频轨道
     */
    void onTrackStarted(MediaStreamTrack track);

    /**
     * 远端视频轨道结束。
     *
     * @param track 视频轨道
     */
    void onTrackStopped(MediaStreamTrack track);

    /**
     * 获取所有远端轨道。
     *
     * @return 轨道列表
     */
    List<MediaStreamTrack> tracks();
}
