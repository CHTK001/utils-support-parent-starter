package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
 * 音乐播放列表摘要
 * 
 * @author CH
 * @since 1.0.0
*/
@Data
@Builder
public class MusicPlaylistSummary {
    private String playlistId;
    private String source;
    private String title;
    private String description;
    private String coverUrl;
    private String author;
    private Integer trackCount;
    private String accentColor;
    private Long playCount;
    private Long commentCount;
}


