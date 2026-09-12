package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
/**
* @author CH
* @since 4.0.0.42
 */

public interface ResourceProvider {

    ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch);
}
