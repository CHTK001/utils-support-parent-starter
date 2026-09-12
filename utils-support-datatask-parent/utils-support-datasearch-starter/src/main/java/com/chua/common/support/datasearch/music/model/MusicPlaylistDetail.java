package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
* 音乐播放列表详情
* 
* @author CH
* @since 4.0.0.42
*/
@Data
@Builder
public class MusicPlaylistDetail {
    /** playlistid */
    private String playlistId;
    /** 来源 */
    private String source;
    /** 标题 */
    private String title;
    /** 描述 */
    private String description;
    /** coverurl */
    private String coverUrl;
    /** 作者 */
    private String author;
    /** Track数量 */
    private Integer trackCount;
    /** Tracks */
    private List<MusicTrackSummary> tracks;
}


