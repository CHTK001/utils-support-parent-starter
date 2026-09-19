package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
 * 音乐播放列表摘要
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class MusicPlaylistSummary {
    /**
     * playlistid
    */
    private String playlistId;
    /**
     * 来源
    */
    private String source;
    /**
     * 标题
    */
    private String title;
    /**
     * 描述
    */
    private String description;
    /**
     * coverurl
    */
    private String coverUrl;
    /**
     * 作者
    */
    private String author;
    /**
     * Track数量
    */
    private Integer trackCount;
    /**
     * Accent颜色
    */
    private String accentColor;
    /**
     * Play数量
    */
    private Long playCount;
    /**
     * 评论数量
    */
    private Long commentCount;
}


