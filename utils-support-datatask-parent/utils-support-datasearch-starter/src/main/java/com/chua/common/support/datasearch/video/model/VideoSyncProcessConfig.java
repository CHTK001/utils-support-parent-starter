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

    /** 创建 视频同步处理配置 实例 */
    public VideoSyncProcessConfig() {
    }

    /**
     * 构建器
     *
     * @return 构建器的结果
     */
    public static VideoSyncProcessConfigBuilder builder() {
        return new VideoSyncProcessConfigBuilder();
    }

    /**
     * 获取视频类型
     *
     * @return 获取视频类型的结果
     */
    public String getVideoType() {
        return videoType;
    }

    /**
     * 设置视频类型
     *
     * @param videoType 视频类型
     */
    public void setVideoType(String videoType) {
        this.videoType = videoType;
    }

    /**
     * 获取当前page入page
     *
     * @return 获取当前page入page的结果
     */
    public int getCurrentPageInPage() {
        return currentPageInPage;
    }

    /**
     * 设置当前page入page
     *
     * @param currentPageInPage 当前page入page
     */
    public void setCurrentPageInPage(int currentPageInPage) {
        this.currentPageInPage = currentPageInPage;
    }

    /**
     * 获取总计入page
     *
     * @return 获取total入page的结果
     */
    public int getTotalInPage() {
        return totalInPage;
    }

    /**
     * 设置总计入page
     *
     * @param totalInPage total入page
     */
    public void setTotalInPage(int totalInPage) {
        this.totalInPage = totalInPage;
    }

    /**
     * 获取Page
     *
     * @return 获取page的结果
     */
    public int getPage() {
        return page;
    }

    /**
     * 设置Page
     *
     * @param page page
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * 获取总计Pages
     *
     * @return 获取totalpages的结果
     */
    public int getTotalPages() {
        return totalPages;
    }

    /**
     * 设置总计Pages
     *
     * @param totalPages totalpages
     * @author CH
     * @since 4.0.0
     */
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

        /**
         * 视频类型
         *
         * @param videoType 视频类型
         * @return 视频类型的结果
         */
        public VideoSyncProcessConfigBuilder videoType(String videoType) {
            this.videoType = videoType;
            return this;
        }

        /**
         * 当前page入page
         *
         * @param currentPageInPage 当前page入page
         * @return 当前page入page的结果
         */
        public VideoSyncProcessConfigBuilder currentPageInPage(int currentPageInPage) {
            this.currentPageInPage = currentPageInPage;
            return this;
        }

        /**
         * 总计入page
         *
         * @param totalInPage total入page
         * @return total入page的结果
         */
        public VideoSyncProcessConfigBuilder totalInPage(int totalInPage) {
            this.totalInPage = totalInPage;
            return this;
        }

        /**
         * Page
         *
         * @param page page
         * @return page的结果
         */
        public VideoSyncProcessConfigBuilder page(int page) {
            this.page = page;
            return this;
        }

        /**
         * 总计Pages
         *
         * @param totalPages totalpages
         * @return totalPages的结果
         */
        public VideoSyncProcessConfigBuilder totalPages(int totalPages) {
            this.totalPages = totalPages;
            return this;
        }

        /**
         * 构建
         *
         * @return 构建的结果
         */
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

