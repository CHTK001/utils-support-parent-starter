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
public class SubtitleSearchResult {
    private String subtitleId;
    private String videoId;
    private String videoName;
    private String language;
    private String subtitleContent;
    private String startTime;
    private String endTime;
    private String source;
    private String videoUrl;
}
