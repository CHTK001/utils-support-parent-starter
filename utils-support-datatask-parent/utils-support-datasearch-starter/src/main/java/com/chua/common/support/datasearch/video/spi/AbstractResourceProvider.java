package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
/**
 * @author CH
 * @since 4.0.0.42
 */

public abstract class AbstractResourceProvider implements ResourceProvider {

    /** 视频数据源 */
    protected VideoSource videoSource;

    /** 创建 AbstractResourceProvider 实例 */
    public AbstractResourceProvider() {
        this.videoSource = new VideoSource();
    }

    /**
     * 创建 AbstractResourceProvider 实例
     * @param videoSource videoSource
     */
    public AbstractResourceProvider(VideoSource videoSource) {
        this.videoSource = videoSource;
    }

    /** 检查 provider 是否被封，如被封则提前返回 */
    protected ReturnPageResult<VideoInfoResult> checkBlocked(String providerName) {
        if (VideoProviderRegistry.isBlocked(providerName)) {
            VideoProviderRegistry.BlockReason reason = VideoProviderRegistry.getBlockReason(providerName);
            return ReturnPageResult.error(
                "provider [" + providerName + "] 已标记为 " + (reason != null ? reason.label() : "blocked")
                + "，跳过执行");
        }
        return null;
    }
}
