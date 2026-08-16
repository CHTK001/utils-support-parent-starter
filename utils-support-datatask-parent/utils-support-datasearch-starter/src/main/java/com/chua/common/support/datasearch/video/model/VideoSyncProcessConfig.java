package com.chua.common.support.datasearch.video.model;

/**
 * 视频同步配置
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSyncProcessConfig {

    private String videoType;
    private int currentPageInPage;
    private int totalInPage;
    private int page;
    private int totalPages;

    public VideoSyncProcessConfig() {
    }

    public static VideoSyncProcessConfigBuilder builder() {
        return new VideoSyncProcessConfigBuilder();
    }

    public String getVideoType() {
        return videoType;
    }

    public void setVideoType(String videoType) {
        this.videoType = videoType;
    }

    public int getCurrentPageInPage() {
        return currentPageInPage;
    }

    public void setCurrentPageInPage(int currentPageInPage) {
        this.currentPageInPage = currentPageInPage;
    }

    public int getTotalInPage() {
        return totalInPage;
    }

    public void setTotalInPage(int totalInPage) {
        this.totalInPage = totalInPage;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public static class VideoSyncProcessConfigBuilder {
        private String videoType;
        private int currentPageInPage;
        private int totalInPage;
        private int page;
        private int totalPages;

        VideoSyncProcessConfigBuilder() {
        }

        public VideoSyncProcessConfigBuilder videoType(String videoType) {
            this.videoType = videoType;
            return this;
        }

        public VideoSyncProcessConfigBuilder currentPageInPage(int currentPageInPage) {
            this.currentPageInPage = currentPageInPage;
            return this;
        }

        public VideoSyncProcessConfigBuilder totalInPage(int totalInPage) {
            this.totalInPage = totalInPage;
            return this;
        }

        public VideoSyncProcessConfigBuilder page(int page) {
            this.page = page;
            return this;
        }

        public VideoSyncProcessConfigBuilder totalPages(int totalPages) {
            this.totalPages = totalPages;
            return this;
        }

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

