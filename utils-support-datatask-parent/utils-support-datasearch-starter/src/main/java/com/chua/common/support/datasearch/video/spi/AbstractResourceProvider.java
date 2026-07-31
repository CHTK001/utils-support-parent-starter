package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.video.model.VideoSource;
/**
 * @author CH
 */

public abstract class AbstractResourceProvider implements ResourceProvider {

    protected VideoSource videoSource;

    public AbstractResourceProvider() {
    }

    public AbstractResourceProvider(VideoSource videoSource) {
        this.videoSource = videoSource;
    }
}
