package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
 * 音乐评论信息
 * 
 * @author CH
 * @since 4.0.0.42
*/
@Data
@Builder
public class MusicComment {
    /** CommentID */
    private String commentId;
    /** Author */
    private String author;
    /** Avatar */
    private String avatar;
    /** 内容 */
    private String content;
    /** Liked数量 */
    private Long likedCount;
    /** 时间 */
    private String time;
}


