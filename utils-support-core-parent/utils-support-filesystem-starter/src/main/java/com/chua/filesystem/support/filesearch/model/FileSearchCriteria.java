package com.chua.filesystem.support.filesearch.model;

import javax.annotation.Nullable;

/**
 * 文件搜索条件
 *
 * @param rootPath        根目录路径，null 表示当前工作目录
 * @param namePattern     文件名通配符模式（如 "*.txt"、"report?.pdf"），null 表示不限
 * @param minSize         最小文件大小（字节），-1 表示不限
 * @param maxSize         最大文件大小（字节），-1 表示不限
 * @param maxResults      最大返回结果数，0 表示不限，默认 1000
 * @param maxDepth        目录遍历最大深度，0 表示不限
 * @param excludeDirs     要排除的目录名列表
 * @param excludePatterns 要排除的文件名模式列表
 * @param followLinks     是否跟踪符号链接，默认 false
 * @param includeHidden   是否包含隐藏文件，默认 false
 * @param sortBy          排序方式：name / size / path / modified
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
     * @author CH
     * @since 4.0.0
     */
    public static final class Builder {
        private String rootPath;
        private String namePattern;
        private long minSize = -1;
        private long maxSize = -1;
        private int maxResults = DEFAULT_MAX_RESULTS;
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private String[] excludeDirs;
        private String[] excludePatterns;
        private boolean followLinks = false;
        private boolean includeHidden = false;
        private String sortBy = SORT_BY_SIZE;
        private String order = ORDER_DESC;

        public Builder rootPath(String rootPath) {
            this.rootPath = rootPath;
            return this;
        }

        public Builder namePattern(String namePattern) {
            this.namePattern = namePattern;
            return this;
        }

        public Builder minSize(long minSize) {
            this.minSize = minSize;
            return this;
        }

        public Builder maxSize(long maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Builder excludeDirs(String... excludeDirs) {
            this.excludeDirs = excludeDirs;
            return this;
        }

        public Builder excludePatterns(String... excludePatterns) {
            this.excludePatterns = excludePatterns;
            return this;
        }

        public Builder followLinks(boolean followLinks) {
            this.followLinks = followLinks;
            return this;
        }

        public Builder includeHidden(boolean includeHidden) {
            this.includeHidden = includeHidden;
            return this;
        }

        public Builder sortBy(String sortBy) {
            this.sortBy = sortBy;
            return this;
        }

        public Builder order(String order) {
            this.order = order;
            return this;
        }

        public FileSearchCriteria build() {
            return new FileSearchCriteria(
                    rootPath, namePattern, minSize, maxSize, maxResults, maxDepth,
                    excludeDirs, excludePatterns, followLinks, includeHidden,
                    sortBy, order
            );
        }
    }
}
