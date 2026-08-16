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

    private String title;
    private String url;
    private PanType panType;
    private String size;
    private String description;
    private LocalDateTime publishTime;
    private String source;
    private Double score;
    private String extractCode;
    private Boolean valid;
    private LocalDateTime createTime;

    public PanResource() {
        this.createTime = LocalDateTime.now();
        this.valid = true;
    }

    public PanResource(String title, String url, PanType panType) {
        this();
        this.title = title;
        this.url = url;
        this.panType = panType;
    }

    public PanResource(String title, String url, PanType panType, String size, String source) {
        this(title, url, panType);
        this.size = size;
        this.source = source;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public PanType getPanType() {
        return panType;
    }

    public void setPanType(PanType panType) {
        this.panType = panType;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getExtractCode() {
        return extractCode;
    }

    public void setExtractCode(String extractCode) {
        this.extractCode = extractCode;
    }

    public Boolean getValid() {
        return valid;
    }

    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}

