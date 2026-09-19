package com.chua.common.support.datasearch.video.model;

import java.time.LocalDateTime;

/**
 * 网盘资Դʵ体?
 * 用于封װ网盘搜索结果
 *
 * @author CH
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
    /** 发布时间 */
    private LocalDateTime publishTime;
    /** 来源 */
    private String source;
    /** 分数 */
    private Double score;
    /** Extract代码 */
    private String extractCode;
    /** 是否有效 */
    private Boolean valid;
    /** 创建时间 */
    private LocalDateTime createTime;

    /** 创建 panresource 实例 */
    public PanResource() {
        this.createTime = LocalDateTime.now();
        this.valid = true;
    }

    /**
     * 创建 panresource 实例
     * @param title title
     * @param title 字符串
     * @param panType pan类型
     * @param url url
     * @param panType pan类型
     */
    public PanResource(String title, String url, PanType panType) {
        this();
        this.title = title;
        this.url = url;
        this.panType = panType;
    }

    /**
     * 创建 panresource 实例
     * @param title title
     * @param title 字符串
     * @param panType pan类型
     * @param title 字符串
     * @param title 字符串
     * @param url url
     * @param panType pan类型
     * @param size 大小
     * @param source 源
     */
    public PanResource(String title, String url, PanType panType, String size, String source) {
        this(title, url, panType);
        this.size = size;
        this.source = source;
    }

    /**
     * 获取Title
     *
     * @return 获取title的结果
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置Title
     *
     * @param title title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 获取Url
     *
     * @return 获取url的结果
     */
    public String getUrl() {
        return url;
    }

    /**
     * 设置Url
     *
     * @param url url
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * 获取pan类型
     *
     * @return 获取pan类型的结果
     */
    public PanType getPanType() {
        return panType;
    }

    /**
     * 设置pan类型
     *
     * @param panType pan类型
     */
    public void setPanType(PanType panType) {
        this.panType = panType;
    }

    /**
     * 获取获取大小
     *
     * @return 获取大小的结果
     */
    public String getSize() {
        return size;
    }

    /**
     * 设置获取大小
     *
     * @param size 大小
     */
    public void setSize(String size) {
        this.size = size;
    }

    /**
     * 获取Description
     *
     * @return 获取description的结果
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置Description
     *
     * @param description description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取发布时间
     *
     * @return 获取发布时间的结果
     */
    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    /**
     * 设置发布时间
     *
     * @param publishTime 发布时间
     */
    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }

    /**
     * 获取源
     *
     * @return 获取源的结果
     */
    public String getSource() {
        return source;
    }

    /**
     * 设置源
     *
     * @param source 源
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * 获取Score
     *
     * @return 获取score的结果
     */
    public Double getScore() {
        return score;
    }

    /**
     * 设置Score
     *
     * @param score score
     */
    public void setScore(Double score) {
        this.score = score;
    }

    /**
     * 获取extract编码
     *
     * @return 获取extract编码的结果
     */
    public String getExtractCode() {
        return extractCode;
    }

    /**
     * 设置extract编码
     *
     * @param extractCode extract编码
     */
    public void setExtractCode(String extractCode) {
        this.extractCode = extractCode;
    }

    /**
     * 获取Valid
     *
     * @return 获取valid的结果
     */
    public Boolean getValid() {
        return valid;
    }

    /**
     * 设置Valid
     *
     * @param valid valid
     */
    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    /**
     * 获取创建时间
     *
     * @return 获取创建时间的结果
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置创建时间
     *
     * @param createTime 创建时间
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}

