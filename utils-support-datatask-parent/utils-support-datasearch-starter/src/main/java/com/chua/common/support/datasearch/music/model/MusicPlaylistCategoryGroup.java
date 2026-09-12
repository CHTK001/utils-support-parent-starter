package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表分类组
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class MusicPlaylistCategoryGroup {
    /** 分组标识 */
    private String groupId;
    /** 名称 */
    private String name;
    /** 标签 */
    private List<MusicPlaylistCategory> tags;
}


