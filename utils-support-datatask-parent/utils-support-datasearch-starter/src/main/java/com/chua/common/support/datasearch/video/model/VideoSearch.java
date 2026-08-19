package com.chua.common.support.datasearch.video.model;

/**
 * VideoSearch ?视Ƶ搜索参数 POJO?
 * <p>
 * ?MyBatis-Plus Query 解耦的纯数据类?
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSearch {

    /** Keyword */
    private String keyword;
    /** Year */
    private Integer year;
    /** 分类 */
    private String category;
    /** 视频类型 */
    private String videoType;
    /** Platform */
    private String platform;
    /** 页 */
    private int page = 1;
    /** 每页大小 */
    private int pageSize = 10;
    /** 排序 */
    private String order;
    /** Prop */
    private String[] prop;

    /** 创建 VideoSearch 实例 */
    public VideoSearch() {}

    /**
     * 创建 VideoSearch 实例
     * @param keyword keyword
     */
    public VideoSearch(String keyword) {
        this.keyword = keyword;
    }

    /** 获取Keyword */
    public String getKeyword() { return keyword; }
    /** 设置Keyword */
    public void setKeyword(String keyword) { this.keyword = keyword; }

    /** 获取Year */
    public Integer getYear() { return year; }
    /** 设置Year */
    public void setYear(Integer year) { this.year = year; }

    /** 获取Category */
    public String getCategory() { return category; }
    /** 设置Category */
    public void setCategory(String category) { this.category = category; }

    /** 获取VideoType */
    public String getVideoType() { return videoType; }
    /** 设置VideoType */
    public void setVideoType(String videoType) { this.videoType = videoType; }

    /** 获取Platform */
    public String getPlatform() { return platform; }
    /** 设置Platform */
    public void setPlatform(String platform) { this.platform = platform; }

    /** 获取Page */
    public int getPage() { return page; }
    /** 设置Page */
    public void setPage(int page) { this.page = page; }

    /** 获取Page获取大小 */
    public int getPageSize() { return pageSize; }
    /** 设置Page获取大小 */
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    /** 获取Order */
    public String getOrder() { return order; }
    /** 设置Order */
    public void setOrder(String order) { this.order = order; }

    /** 获取Prop */
    public String[] getProp() { return prop; }
    /** 设置Prop */
    public void setProp(String[] prop) { this.prop = prop; }
}

