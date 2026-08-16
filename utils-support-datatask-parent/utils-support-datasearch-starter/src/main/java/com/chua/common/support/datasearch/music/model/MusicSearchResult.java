package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐搜索结果
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class MusicSearchResult {
    private String source;
    private String keyword;
    private Integer page;
    private Integer pageSize;
    private Long total;
    private List<MusicTrackSummary> tracks;
}


