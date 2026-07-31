package com.chua.common.support.datasearch.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author CH
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleSearchRequest {
    private String keyword;
    private String videoId;
    private String language;
    @Builder.Default
    private int page = 1;
    @Builder.Default
    private int pageSize = 10;
}
