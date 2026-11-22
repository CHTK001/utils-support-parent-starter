package com.chua.common.support.ai.news;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * AIHOT（aihot.news）精选资讯条目（RSS item 的结构化结果）。
 *
 * <p>{@code description} 保留 RSS 里的原始 HTML；{@code descriptionText} 是
 * 去标签后的纯文本摘要（供落库与前端展示）；{@code originUrl} 是从描述里
 * 解析出的原文链接（AIHOT 站外地址），没有原文时为 {@code null}。</p>
 *
 * @author CH
 * @since 4.0.0.70
 */
@Data
@Builder
public class AiHotNewsItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 资讯标题
     */
    private String title;

    /**
     * AIHOT 站内链接（唯一去重键）
     */
    private String link;

    /**
     * 原始描述（HTML，可能为空）
     */
    private String description;

    /**
     * 纯文本摘要（去标签、去实体、合并空白）
     */
    private String descriptionText;

    /**
     * 原文链接（AIHOT 站外地址，未解析出时为 null）
     */
    private String originUrl;

    /**
     * 分类（RSS category，如「教程」「行业」）
     */
    private String category;

    /**
     * 发布时间（毫秒；RSS pubDate 解析失败时为 null）
     */
    private Long publishedAt;
}
