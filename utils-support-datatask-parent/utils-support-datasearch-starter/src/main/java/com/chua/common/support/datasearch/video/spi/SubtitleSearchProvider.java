package com.chua.common.support.datasearch.video.spi;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.SubtitleSearchRequest;
import com.chua.common.support.datasearch.video.model.SubtitleSearchResult;
/**
 * @author CH
 */

public interface SubtitleSearchProvider {
    ReturnPageResult<SubtitleSearchResult> searchSubtitles(SubtitleSearchRequest request);
}
