package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
 * 音乐音轨摘要
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class MusicTrackSummary {
    /** TrackID */
    private String trackId;
    /** 来源 */
    private String source;
    /** 标题 */
    private String title;
    /** Artist */
    private String artist;
    /** Album */
    private String album;
    /** CoverURL */
    private String coverUrl;
    /** 持续时间秒 */
    private Integer durationSeconds;
    /** Play数量 */
    private Long playCount;
    /** Comment数量 */
    private Long commentCount;
}


