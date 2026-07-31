package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表分类组
 * 
 * @author CH
 * @since 1.0.0
*/
@Data
@Builder
public class MusicPlaylistCategoryGroup {
    private String groupId;
    private String name;
    private List<MusicPlaylistCategory> tags;
}


