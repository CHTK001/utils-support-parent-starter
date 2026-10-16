package com.chua.common.support.datasearch.emoji.model;

import com.chua.common.support.collection.wrap.Wraps;

import java.util.List;
import java.util.Map;

/**
 * 表情包信息模型。
 *
 * <p>对应一个可检索、可下载的表情条目：来源站点、标题、缩略图 / 原图地址、
 * 详情页地址与标签。不同数据源的字段填充程度可能不同，允许部分字段为空。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EmojiInfo {

    /**
     * 表情 ID（来源站点的内部编号，可能为空）
     */
    private final String id;

    /**
     * 表情标题 / 描述
     */
    private final String title;

    /**
     * 原图（大图）地址
     */
    private final String imageUrl;

    /**
     * 缩略图地址
     */
    private final String thumbUrl;

    /**
     * 详情页地址（可能为空）
     */
    private final String detailUrl;

    /**
     * 数据源名称（如 doutula、bing）
     */
    private final String source;

    /**
     * 标签列表（如 狗子、动图），可能为空
     */
    private final List<String> tags;

    /**
     * 创建表情包信息实例。
     *
     * @param id        表情 ID
     * @param title     标题
     * @param imageUrl  原图地址
     * @param thumbUrl  缩略图地址
     * @param detailUrl 详情页地址
     * @param source    数据源名称
     * @param tags      标签列表
     */
    public EmojiInfo(String id, String title, String imageUrl, String thumbUrl,
                     String detailUrl, String source, List<String> tags) {
        this.id = id;
        this.title = title;
        this.imageUrl = imageUrl;
        this.thumbUrl = thumbUrl;
        this.detailUrl = detailUrl;
        this.source = source;
        this.tags = tags;
    }

    /**
     * 获取表情 ID。
     *
     * @return 表情 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取标题。
     *
     * @return 标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取原图地址。
     *
     * @return 原图地址
     */
    public String getImageUrl() {
        return imageUrl;
    }

    /**
     * 获取缩略图地址。
     *
     * @return 缩略图地址
     */
    public String getThumbUrl() {
        return thumbUrl;
    }

    /**
     * 获取详情页地址。
     *
     * @return 详情页地址
     */
    public String getDetailUrl() {
        return detailUrl;
    }

    /**
     * 获取数据源名称。
     *
     * @return 数据源名称
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取标签列表。
     *
     * @return 标签列表
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * 转换为 映射 用于 JSON 序列化。
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = Wraps.linkedMap();
        map.put("id", id);
        map.put("title", title);
        map.put("imageUrl", imageUrl);
        map.put("thumbUrl", thumbUrl);
        map.put("detailUrl", detailUrl);
        map.put("source", source);
        map.put("tags", tags);
        return map;
    }
}
