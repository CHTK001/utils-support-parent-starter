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
    /** 源 */
    private List<MusicSourceOption> sources;
    /** 默认来源 */
    private String defaultSource;
    /** hotkeywords */
    private List<String> hotKeywords;
    /** Featuredplaylists */
    private List<MusicPlaylistSummary> featuredPlaylists;
}


