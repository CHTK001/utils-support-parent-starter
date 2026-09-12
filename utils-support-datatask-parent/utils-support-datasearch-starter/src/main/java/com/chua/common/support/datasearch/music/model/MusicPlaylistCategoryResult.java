package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 音乐播放列表分类结果
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class MusicPlaylistCategoryResult {
    /** 来源 */
    private String source;
    /** 标签标识 */
    private String tagId;
    /** 分类名称 */
    private String categoryName;
    /** 页 */
    private Integer page;
    /** 每页大小 */
    private Integer pageSize;
    /** 总数 */
    private Long total;
    /** Playlists */
    private List<MusicPlaylistSummary> playlists;
}


