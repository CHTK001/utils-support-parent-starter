package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
/**
 * @author CH
 */

public interface ResourceProvider {

    ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch);
}
