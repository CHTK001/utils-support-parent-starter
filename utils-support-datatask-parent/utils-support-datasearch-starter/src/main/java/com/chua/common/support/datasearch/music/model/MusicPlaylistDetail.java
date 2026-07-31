package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表详情
 * 
 * @author CH
 * @since 1.0.0
*/
@Data
@Builder
public class MusicPlaylistDetail {
    private String playlistId;
    private String source;
    private String title;
    private String description;
    private String coverUrl;
    private String author;
    private Integer trackCount;
    private List<MusicTrackSummary> tracks;
}


