package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐概览信息
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class MusicOverview {
    private List<MusicSourceOption> sources;
    private String defaultSource;
    private List<String> hotKeywords;
    private List<MusicPlaylistSummary> featuredPlaylists;
}


