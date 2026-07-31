package com.chua.common.support.datasearch.video.model;

/**
 * VideoSearch ?视Ƶ搜索参数 POJO?
 * <p>
 * ?MyBatis-Plus Query 解耦的纯数据类?
 *
 * @author CH
 * @since 4.0.0.41
 */
public class VideoSearch {

    private String keyword;
    private Integer year;
    private String category;
    private String videoType;
    private String platform;
    private int page = 1;
    private int pageSize = 10;
    private String order;
    private String[] prop;

    public VideoSearch() {}

    public VideoSearch(String keyword) {
        this.keyword = keyword;
    }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getVideoType() { return videoType; }
    public void setVideoType(String videoType) { this.videoType = videoType; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public String getOrder() { return order; }
    public void setOrder(String order) { this.order = order; }

    public String[] getProp() { return prop; }
    public void setProp(String[] prop) { this.prop = prop; }
}

