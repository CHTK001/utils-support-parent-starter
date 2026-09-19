package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
 * 音乐播放列表分类
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class MusicPlaylistCategory {
    /**
     * 标签标识
    */
    private String tagId;
    /**
     * 名称
    */
    private String name;
    /**
     * HOT
    */
    private Boolean hot;
}


