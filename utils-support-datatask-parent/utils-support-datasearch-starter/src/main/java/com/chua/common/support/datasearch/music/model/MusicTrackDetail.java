package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐音轨详情
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class MusicTrackDetail {
    /**
     * trackid
    */
    private String trackId;
    /**
     * 来源
    */
    private String source;
    /**
     * 标题
    */
    private String title;
    /**
     * Artist
    */
    private String artist;
    /**
     * Album
    */
    private String album;
    /**
     * coverurl
    */
    private String coverUrl;
    /**
     * 流URL
    */
    private String streamUrl;
    /**
     * 持续时间秒
    */
    private Integer durationSeconds;
    /**
     * Lyrics
    */
    private String lyrics;
    /**
     * Play数量
    */
    private Long playCount;
    /**
     * 评论数量
    */
    private Long commentCount;
    /**
     * 评论
    */
    private List<MusicComment> comments;
}


