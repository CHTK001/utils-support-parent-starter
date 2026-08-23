package com.chua.common.support.datasearch.video.spi;

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
    }

    /**
     * 创建 AbstractResourceProvider 实例
     * @param videoSource videoSource
     */
    public AbstractResourceProvider(VideoSource videoSource) {
        this.videoSource = videoSource;
    }
}
