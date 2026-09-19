package com.chua.filesystem.support.filesearch.model;

import javax.annotation.Nullable;

/**
 * 文件搜索条件
 *
 * @param rootPath        根目录路径，空 表示当前工作目录
 * @param namePattern     文件名通配符模式（如 "*.txt"、"report?.pdf"），空 表示不限
 * @param minSize         最小文件大小（字节），-1 表示不限
 * @param maxSize         最大文件大小（字节），-1 表示不限
 * @param maxResults      最大返回结果数，0 表示不限，默认 1000
 * @param maxDepth        目录遍历最大深度，0 表示不限
 * @param excludeDirs     要排除的目录名列表
 * @param excludePatterns 要排除的文件名模式列表
 * @param followLinks     是否跟踪符号链接，默认 false
 * @param includeHidden   是否包含隐藏文件，默认 false
 * @param sortBy          排序方式：名称 / 大小 / 路径 / modified
 * @param order           排序方向：asc / desc
 *
 * @author CH
 * @since 4.0.0
 */
public record FileSearchCriteria(
        @Nullable String rootPath,
        @Nullable String namePattern,
        long minSize,
        long maxSize,
        int maxResults,
        int maxDepth,
        @Nullable String[] excludeDirs,
        @Nullable String[] excludePatterns,
        boolean followLinks,
        boolean includeHidden,
        @Nullable String sortBy,
        String order
) {

    /**
     * 升序（最早/最小在前）
     */
    public static final String ORDER_ASC = "asc";
    /**
     * 降序（最新/最大在前，默认）
     */
    public static final String ORDER_DESC = "desc";

    /**
     * 按名称排序
     */
    public static final String SORT_BY_NAME = "name";
    /**
     * 按大小排序
     */
    public static final String SORT_BY_SIZE = "size";
    /**
     * 按路径排序
     */
    public static final String SORT_BY_PATH = "path";
    /**
     * 按修改时间排序
     */
    public static final String SORT_BY_MODIFIED = "modified";

    /**
     * 默认最大结果数
     */
    public static final int DEFAULT_MAX_RESULTS = 1000;
    /**
     * 默认最大遍历深度（0 = 不限）
     */
    public static final int DEFAULT_MAX_DEPTH = 0;

    public FileSearchCriteria {
        if (maxResults < 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        if (order == null || order.isBlank()) {
            order = ORDER_DESC;
        }
        if (sortBy == null || sortBy.isBlank()) {
            sortBy = SORT_BY_SIZE;
        }
    }

    /**
     * 创建构建器
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 链式构建器
     *
     * @since 4.0.0.42
     */
    public static final class Builder {
        /** 根级路径 */
        private String rootPath;
        /** 名称模式 */
        private String namePattern;
        /** 最小值尺寸 */
        private long minSize = -1;
        /** 最大值尺寸 */
        private long maxSize = -1;
        /** 最大值结果 */
        private int maxResults = DEFAULT_MAX_RESULTS;
        /** 最大值深度 */
        private int maxDepth = DEFAULT_MAX_DEPTH;
        /** Excludedirs */
        private String[] excludeDirs;
        /** Excludepatterns */
        private String[] excludePatterns;
        /** Followlinks */
        private boolean followLinks = false;
        /** Includehidden */
        private boolean includeHidden = false;
        /** 排序by */
        private String sortBy = SORT_BY_SIZE;
        /** 排序 */
        private String order = ORDER_DESC;

        /**
         * 根路径
         *
         * @param rootPath 根路径
         * @return 根路径的结果
         */
        public Builder rootPath(String rootPath) {
            this.rootPath = rootPath;
            return this;
        }

        /**
         * 名称模式
         *
         * @param namePattern 名称模式
         * @return 名称模式的结果
         */
        public Builder namePattern(String namePattern) {
            this.namePattern = namePattern;
            return this;
        }

        /**
         * 最小值获取大小
         *
         * @param minSize 最小大小
         * @return 最小大小的结果
         */
        public Builder minSize(long minSize) {
            this.minSize = minSize;
            return this;
        }

        /**
         * 最大值获取大小
         *
         * @param maxSize 最大大小
         * @return 最大大小的结果
         */
        public Builder maxSize(long maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /**
         * 最大值结果
         *
         * @param maxResults 最大结果
         * @return 最大结果的结果
         */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /**
         * 最大值深度
         *
         * @param maxDepth 最大深度
         * @return 最大深度的结果
         */
        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        /**
         * excludedirs
         *
         * @param excludeDirs excludedirs
         * @return excludeDirs的结果
         */
        public Builder excludeDirs(String... excludeDirs) {
            this.excludeDirs = excludeDirs;
            return this;
        }

        /**
         * exclude模式
         *
         * @param excludePatterns exclude模式
         * @return exclude模式的结果
         */
        public Builder excludePatterns(String... excludePatterns) {
            this.excludePatterns = excludePatterns;
            return this;
        }

        /**
         * follow链接
         *
         * @param followLinks follow链接
         * @return follow链接的结果
         */
        public Builder followLinks(boolean followLinks) {
            this.followLinks = followLinks;
            return this;
        }

        /**
         * includehidden
         *
         * @param includeHidden includehidden
         * @return includeHidden的结果
         */
        public Builder includeHidden(boolean includeHidden) {
            this.includeHidden = includeHidden;
            return this;
        }

        /**
         * 排序By
         *
         * @param sortBy 排序by
         * @return 排序by的结果
         */
        public Builder sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        /**
         * 订单
         *
         * @param order 订单
         * @return 订单的结果
         */
        public Builder order(String order) {
            this.order = order;
            return this;
        }

        /**
         * 构建
         *
         * @return 构建的结果
         */
        public FileSearchCriteria build() {
            return new FileSearchCriteria(
                    rootPath, namePattern, minSize, maxSize, maxResults, maxDepth,
                    excludeDirs, excludePatterns, followLinks, includeHidden,
                    sortBy, order
            );
        }
    }
}
