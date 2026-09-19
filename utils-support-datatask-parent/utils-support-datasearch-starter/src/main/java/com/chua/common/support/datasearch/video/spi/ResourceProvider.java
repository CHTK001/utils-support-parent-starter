package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
/**
 * @author CH
 * @since 4.0.0.42
 */

public interface ResourceProvider {

    /**
     * 搜索Resource。
     *
     * @param videoSearch video搜索，不允许为 null
     * @return Return页结果 对象
     */
    ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch);
}
