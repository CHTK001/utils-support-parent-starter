package com.chua.common.support.datasearch.video.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubtitleSearchResult {
    /** subtitleid */
    private String subtitleId;
    /** 视频标识 */
    private String videoId;
    /** 视频名称 */
    private String videoName;
    /** 语言 */
    private String language;
    /** Subtitle内容 */
    private String subtitleContent;
    /** 开始时间 */
    private String startTime;
    /** 结束时间 */
    private String endTime;
    /** 来源 */
    private String source;
    /** 视频URL */
    private String videoUrl;
}
