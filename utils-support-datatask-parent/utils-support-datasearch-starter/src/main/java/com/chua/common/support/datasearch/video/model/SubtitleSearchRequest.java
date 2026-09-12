package com.chua.common.support.datasearch.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleSearchRequest {
    /** Keyword */
    private String keyword;
    /** 视频标识 */
    private String videoId;
    /** 语言 */
    private String language;
    @Builder.Default
    /** 页 */
    private int page = 1;
    @Builder.Default
    /** 每页大小 */
    private int pageSize = 10;
}
