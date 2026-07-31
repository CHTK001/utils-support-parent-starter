package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表搜索结果
 * 
 * @author CH
 * @since 1.0.0
*/
@Data
@Builder
public class MusicPlaylistSearchResult {
    private String source;
    private String keyword;
    private Integer page;
    private Integer pageSize;
    private Long total;
    private List<MusicPlaylistSummary> playlists;
}


