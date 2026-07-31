package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表分类目录
 * 
 * @author CH
 * @since 1.0.0
*/
@Data
@Builder
public class MusicPlaylistCategoryCatalog {
    private String source;
    private List<MusicPlaylistCategory> hotTags;
    private List<MusicPlaylistCategoryGroup> groups;
}


