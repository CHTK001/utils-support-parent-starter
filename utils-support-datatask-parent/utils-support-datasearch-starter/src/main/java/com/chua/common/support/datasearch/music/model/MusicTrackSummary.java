package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
 * 音乐音轨摘要
 * 
 * @author CH
 * @since 1.0.0
*/
@Data
@Builder
public class MusicTrackSummary {
    private String trackId;
    private String source;
    private String title;
    private String artist;
    private String album;
    private String coverUrl;
    private Integer durationSeconds;
    private Long playCount;
    private Long commentCount;
}


