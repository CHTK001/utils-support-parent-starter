package com.chua.common.support.datasearch.video.model;

/**
 * 视频同步配置
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSyncProcessConfig {

    /** 视频类型 */
    private String videoType;
    /** 当前页内页码 */
    private int currentPageInPage;
    /** 页内总数 */
    private int totalInPage;
    /** 页码 */
    private int page;
    /** 总页数 */
    private int totalPages;

    /** 创建 VideoSyncProcessConfig 实例 */
    public VideoSyncProcessConfig() {
    }

    /** Builder */
    public static VideoSyncProcessConfigBuilder builder() {
        return new VideoSyncProcessConfigBuilder();
    }

    /** 获取VideoType */
    public String getVideoType() {
        return videoType;
    }

    /** 设置VideoType */
    public void setVideoType(String videoType) {
        this.videoType = videoType;
    }

    /** 获取CurrentPageInPage */
    public int getCurrentPageInPage() {
        return currentPageInPage;
    }

    /** 设置CurrentPageInPage */
    public void setCurrentPageInPage(int currentPageInPage) {
        this.currentPageInPage = currentPageInPage;
    }

    /** 获取总计InPage */
    public int getTotalInPage() {
        return totalInPage;
    }

    /** 设置总计InPage */
    public void setTotalInPage(int totalInPage) {
        this.totalInPage = totalInPage;
    }

    /** 获取Page */
    public int getPage() {
        return page;
    }

    /** 设置Page */
    public void setPage(int page) {
        this.page = page;
    }

    /** 获取总计Pages */
    public int getTotalPages() {
        return totalPages;
    }

    /** 设置总计Pages */
    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public static class VideoSyncProcessConfigBuilder {
        /** 视频类型 */
        private String videoType;
        /** 当前页内页码 */
        private int currentPageInPage;
        /** 页内总数 */
        private int totalInPage;
        /** 页码 */
        private int page;
        /** 总页数 */
        private int totalPages;

        VideoSyncProcessConfigBuilder() {
        }

        /** VideoType */
        public VideoSyncProcessConfigBuilder videoType(String videoType) {
            this.videoType = videoType;
            return this;
        }

        /** CurrentPageInPage */
        public VideoSyncProcessConfigBuilder currentPageInPage(int currentPageInPage) {
            this.currentPageInPage = currentPageInPage;
            return this;
        }

        /** 总计InPage */
        public VideoSyncProcessConfigBuilder totalInPage(int totalInPage) {
            this.totalInPage = totalInPage;
            return this;
        }

        /** Page */
        public VideoSyncProcessConfigBuilder page(int page) {
            this.page = page;
            return this;
        }

        /** 总计Pages */
        public VideoSyncProcessConfigBuilder totalPages(int totalPages) {
            this.totalPages = totalPages;
            return this;
        }

        /** 构建 */
        public VideoSyncProcessConfig build() {
            VideoSyncProcessConfig config = new VideoSyncProcessConfig();
            config.setVideoType(videoType);
            config.setCurrentPageInPage(currentPageInPage);
            config.setTotalInPage(totalInPage);
            config.setPage(page);
            config.setTotalPages(totalPages);
            return config;
        }
    }
}

