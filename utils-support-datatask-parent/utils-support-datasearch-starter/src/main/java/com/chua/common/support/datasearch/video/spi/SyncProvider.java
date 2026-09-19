package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.video.model.VideoSyncConfig;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSyncProcessConfig;

import java.util.function.BiConsumer;

/**
 * 视Ƶͬ步提供者接?
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SyncProvider {


    /**
     * 从视ƵԴ获ȡ视Ƶ列表
     *
     * @param config   ͬ步配置信Ϣ
     *                 <pre><code>
     *                             VideoSyncConfig config = new VideoSyncConfig();
     *                             config.setVideoSyncConfigUrl("https://example.com/api/videos");
     *                             config.setVideoSyncConfigKey("your-access-key");
     *                             </code></pre>
     * @param consumer 视Ƶ信Ϣ消费处理器，用于处理获ȡ到的ÿ个视Ƶ信Ϣ
     *                 <pre><code>
     *                             Consumer<VideoInfoResult> consumer = videoInfo -> {
     *                                 System.out.println("处理视Ƶ: " + videoInfo.getVideoTitle());
     *                             };
     *                             </code></pre>
     * @throws RuntimeException 当ͬ步过程中发生错误ʱ抛?
     */
    void fetchVideos(VideoSyncConfig config, BiConsumer<VideoInfoResult, VideoSyncProcessConfig> consumer);
}

