package com.chua.common.support.datasearch.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字幕搜索请求参数。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleSearchRequest {
    /**
     * Keyword
    */
    private String keyword;
    /**
     * 视频标识
    */
    private String videoId;
    /**
     * 语言
    */
    private String language;
    /**
     * 页
    */
    @Builder.Default
    private int page = 1;
    /**
     * 每页大小
    */
    @Builder.Default
    private int pageSize = 10;
}
