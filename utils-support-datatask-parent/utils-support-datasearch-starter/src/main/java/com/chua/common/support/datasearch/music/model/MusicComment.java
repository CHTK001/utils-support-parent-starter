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
    private String commentId;
    private String author;
    private String avatar;
    private String content;
    private Long likedCount;
    private String time;
}


