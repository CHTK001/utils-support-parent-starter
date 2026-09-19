package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表分类目录
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class MusicPlaylistCategoryCatalog {
    /** 来源 */
    private String source;
    /** hottags */
    private List<MusicPlaylistCategory> hotTags;
    /** 群体 */
    private List<MusicPlaylistCategoryGroup> groups;
}


