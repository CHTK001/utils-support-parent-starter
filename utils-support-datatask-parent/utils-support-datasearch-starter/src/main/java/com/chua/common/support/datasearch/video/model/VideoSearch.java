package com.chua.common.support.datasearch.video.model;

/**
* 视频搜索 ?视Ƶ搜索参数 POJO?
* <p>
* ?MyBatis-Plus 查询 解耦的纯数据类?
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

    /** 创建 视频搜索 实例 */
    public VideoSearch() {}

    /**
    * 创建 视频搜索 实例
    * @param keyword keyword
    */
    public VideoSearch(String keyword) {
        this.keyword = keyword;
    }

    /**
    * 获取Keyword
    *
    * @return 获取keyword的结果
    */
    public String getKeyword() { return keyword; }
    /**
    * 设置Keyword
    *
    * @param keyword keyword
    */
    public void setKeyword(String keyword) { this.keyword = keyword; }

    /**
    * 获取Year
    *
    * @return 获取year的结果
    */
    public Integer getYear() { return year; }
    /**
    * 设置Year
    *
    * @param year year
    */
    public void setYear(Integer year) { this.year = year; }

    /**
    * 获取分类
    *
    * @return 获取分类的结果
    */
    public String getCategory() { return category; }
    /**
    * 设置分类
    *
    * @param category 分类
    */
    public void setCategory(String category) { this.category = category; }

    /**
    * 获取视频类型
    *
    * @return 获取视频类型的结果
    */
    public String getVideoType() { return videoType; }
    /**
    * 设置视频类型
    *
    * @param videoType 视频类型
    */
    public void setVideoType(String videoType) { this.videoType = videoType; }

    /**
    * 获取Platform
    *
    * @return 获取platform的结果
    */
    public String getPlatform() { return platform; }
    /**
    * 设置Platform
    *
    * @param platform platform
    */
    public void setPlatform(String platform) { this.platform = platform; }

    /**
    * 获取Page
    *
    * @return 获取page的结果
    */
    public int getPage() { return page; }
    /**
    * 设置Page
    *
    * @param page page
    */
    public void setPage(int page) { this.page = page; }

    /**
    * 获取Page获取大小
    *
    * @return 获取page大小的结果
    */
    public int getPageSize() { return pageSize; }
    /**
    * 设置Page获取大小
    *
    * @param pageSize page大小
    */
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    /**
    * 获取订单
    *
    * @return 获取订单的结果
    */
    public String getOrder() { return order; }
    /**
    * 设置订单
    *
    * @param order 订单
    */
    public void setOrder(String order) { this.order = order; }

    /**
    * 获取Prop
    *
    * @return 获取prop的结果
    */
    public String[] getProp() { return prop; }
    /**
    * 设置Prop
    *
    * @param prop prop
    */
    public void setProp(String[] prop) { this.prop = prop; }
}

