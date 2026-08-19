package com.chua.common.support.datasearch.video.model;

import java.time.LocalDateTime;

/**
 * 网盘资Դʵ体?
 * 用于封װ网盘搜索结果
 *
 * @author CH
 * @version 1.0
 * @since 4.0.0.42
 */
public class PanResource {

    /** 标题 */
    private String title;
    /** URL */
    private String url;
    /** PAN类型 */
    private PanType panType;
    /** 尺寸 */
    private String size;
    /** 描述 */
    private String description;
    /** Publish时间 */
    private LocalDateTime publishTime;
    /** 来源 */
    private String source;
    /** 分数 */
    private Double score;
    /** Extract代码 */
    private String extractCode;
    /** 是否有效 */
    private Boolean valid;
    /** Create时间 */
    private LocalDateTime createTime;

    /** 创建 PanResource 实例 */
    public PanResource() {
        this.createTime = LocalDateTime.now();
        this.valid = true;
    }

    /**
     * 创建 PanResource 实例
     * @param title title
     * @param String String
     * @param PanType PanType
     */
    public PanResource(String title, String url, PanType panType) {
        this();
        this.title = title;
        this.url = url;
        this.panType = panType;
    }

    /**
     * 创建 PanResource 实例
     * @param title title
     * @param String String
     * @param PanType PanType
     * @param String String
     * @param String String
     */
    public PanResource(String title, String url, PanType panType, String size, String source) {
        this(title, url, panType);
        this.size = size;
        this.source = source;
    }

    /** 获取Title */
    public String getTitle() {
        return title;
    }

    /** 设置Title */
    public void setTitle(String title) {
        this.title = title;
    }

    /** 获取Url */
    public String getUrl() {
        return url;
    }

    /** 设置Url */
    public void setUrl(String url) {
        this.url = url;
    }

    /** 获取PanType */
    public PanType getPanType() {
        return panType;
    }

    /** 设置PanType */
    public void setPanType(PanType panType) {
        this.panType = panType;
    }

    /** 获取获取大小 */
    public String getSize() {
        return size;
    }

    /** 设置获取大小 */
    public void setSize(String size) {
        this.size = size;
    }

    /** 获取Description */
    public String getDescription() {
        return description;
    }

    /** 设置Description */
    public void setDescription(String description) {
        this.description = description;
    }

    /** 获取发布Time */
    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    /** 设置发布Time */
    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }

    /** 获取Source */
    public String getSource() {
        return source;
    }

    /** 设置Source */
    public void setSource(String source) {
        this.source = source;
    }

    /** 获取Score */
    public Double getScore() {
        return score;
    }

    /** 设置Score */
    public void setScore(Double score) {
        this.score = score;
    }

    /** 获取ExtractCode */
    public String getExtractCode() {
        return extractCode;
    }

    /** 设置ExtractCode */
    public void setExtractCode(String extractCode) {
        this.extractCode = extractCode;
    }

    /** 获取Valid */
    public Boolean getValid() {
        return valid;
    }

    /** 设置Valid */
    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    /** 获取创建Time */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /** 设置创建Time */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}

