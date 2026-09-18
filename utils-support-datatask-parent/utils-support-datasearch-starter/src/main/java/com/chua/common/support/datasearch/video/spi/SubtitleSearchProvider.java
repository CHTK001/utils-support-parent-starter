package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.SubtitleSearchRequest;
import com.chua.common.support.datasearch.video.model.SubtitleSearchResult;
/**
* @author CH
* @since 4.0.0.42
 */

public interface SubtitleSearchProvider {
    /**
     * 搜索Subtitles。
     *
     * @param request 请求，不允许为 null
     * @return Return页结果 对象
     */
    ReturnPageResult<SubtitleSearchResult> searchSubtitles(SubtitleSearchRequest request);
}
