package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.video.model.VideoSource;
/**
 * @since 4.0.0.42
 */

public abstract class AbstractResourceProvider implements ResourceProvider {

    /** 视频数据源 */
    protected VideoSource videoSource;

    public AbstractResourceProvider() {
    }

    public AbstractResourceProvider(VideoSource videoSource) {
        this.videoSource = videoSource;
    }
}
