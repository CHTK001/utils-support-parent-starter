package com.chua.filesystem.log.support.model;

import javax.annotation.Nullable;

/**
 * 系统日志查询条件
 *
 * @param source     日志源 (如 系统, Application, 安全性, journald)，空 表示所有源
 * @param pattern    通配符搜索模式 (如 "*disk*")，空 表示不过滤
 * @param minLevel   最低日志级别，空 表示所有级别
 * @param maxResults 最大返回条数，默认 100
 * @param after      分页游标 (上一页最后一条的游标值)，空 表示第一页
 * @param tail       是否持续监听新日志 (类似 tail -f)，默认 false
 * @param order      排序方向: "desc" (默认, 最新在前) / "asc" (最早在前)
 *
 * @author CH
 * @since 4.0.0.42
 */
public record LogQuery(
        @Nullable String source,
        @Nullable String pattern,
        @Nullable LogLevel minLevel,
        int maxResults,
        @Nullable String after,
        boolean tail,
        String order
) {

    /**
     * 降序 (最新在前)
     */
    public static final String ORDER_DESC = "desc";
    /**
     * 升序 (最早在前)
     */
    public static final String ORDER_ASC = "asc";

    /**
     * 默认最大结果数
     */
    public static final int DEFAULT_MAX_RESULTS = 100;

    public LogQuery {
        if (maxResults <= 0) {
            maxResults = DEFAULT_MAX_RESULTS;
        }
        if (order == null || order.isBlank()) {
            order = ORDER_DESC;
        }
    }

    /**
     * 的
     *
     * @param pattern 模式
     * @return 的的结果
     */
    public static LogQuery of(String pattern) {
        return new LogQuery(null, pattern, null, DEFAULT_MAX_RESULTS, null, false, ORDER_DESC);
    }

    /**
     * 的
     *
     * @param pattern 模式
     * @param minLevel 最小级别
     * @return 的的结果
     */
    public static LogQuery of(String pattern, LogLevel minLevel) {
        return new LogQuery(null, pattern, minLevel, DEFAULT_MAX_RESULTS, null, false, ORDER_DESC);
    }

    /**
     * 构建器
     *
     * @return 构建器的结果
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        /**
         * 来源
        */
        private String source;
        /**
         * 模式
        */
        private String pattern;
        /**
         * 最小值级别
        */
        private LogLevel minLevel;
        /**
         * 最大值结果
        */
        private int maxResults = DEFAULT_MAX_RESULTS;
        /**
         * 之后
        */
        private String after;
        /**
         * Tail
        */
        private boolean tail;
        /**
         * 排序
        */
        private String order = ORDER_DESC;

        /**
         * 源
         *
         * @param source 源
         * @return 源的结果
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * 模式
         *
         * @param pattern 模式
         * @return 模式的结果
         */
        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        /**
         * 最小值级别
         *
         * @param minLevel 最小级别
         * @return 最小级别的结果
         */
        public Builder minLevel(LogLevel minLevel) {
            this.minLevel = minLevel;
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
         * 之后
         *
         * @param after 之后
         * @return 之后的结果
         */
        public Builder after(String after) {
            this.after = after;
            return this;
        }

        /**
         * Tail
         *
         * @param tail tail
         * @return tail的结果
         */
        public Builder tail(boolean tail) {
            this.tail = tail;
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
        public LogQuery build() {
            return new LogQuery(source, pattern, minLevel, maxResults, after, tail, order);
        }
    }
}
