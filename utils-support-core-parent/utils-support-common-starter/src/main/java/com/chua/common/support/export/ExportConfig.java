package com.chua.common.support.export;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 导出配置。
 *
 * <p>不可变，通过 {@link #builder()} 构建。所有字段为空表示"沿用注解 / 实现默认值"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ExportConfig {

    /**
     * 空配置。
     */
    public static final ExportConfig EMPTY = builder().build();

    /**
     * 目标格式，如 {@code xlsx}。
     */
    private final String format;

    /**
     * 显式指定的引擎名（SPI 别名），为空则按格式自动选择。
     */
    private final String engine;

    /**
     * 字符集。
     */
    private final Charset charset;

    /**
     * 默认日期格式。
     */
    private final String dateFormat;

    /**
     * 模板模式下的主数据标签名。
     */
    private final String tableTag;

    /**
     * 模板标签前缀。
     */
    private final String tagPrefix;

    /**
     * 模板标签后缀。
     */
    private final String tagSuffix;

    /**
     * 是否使用流式写出（大 Excel 低内存）。
     */
    private final boolean streaming;

    /**
     * 导出结束后是否由本框架关闭调用方传入的目标流与模板流。
     */
    private final boolean autoCloseStream;

    /**
     * 构造导出配置。
     *
     * @param builder 构建器
     */
    private ExportConfig(Builder builder) {
        this.format = builder.format;
        this.engine = builder.engine;
        this.charset = builder.charset == null ? StandardCharsets.UTF_8 : builder.charset;
        this.dateFormat = builder.dateFormat;
        this.tableTag = builder.tableTag == null || builder.tableTag.isBlank() ? "list" : builder.tableTag;
        this.tagPrefix = builder.tagPrefix == null || builder.tagPrefix.isEmpty() ? "{{" : builder.tagPrefix;
        this.tagSuffix = builder.tagSuffix == null || builder.tagSuffix.isEmpty() ? "}}" : builder.tagSuffix;
        this.streaming = builder.streaming;
        this.autoCloseStream = builder.autoCloseStream;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取目标格式。
     *
     * @return 格式标识
     */
    public String getFormat() {
        return format;
    }

    /**
     * 获取引擎名。
     *
     * @return 引擎名，可能为 {@code null}
     */
    public String getEngine() {
        return engine;
    }

    /**
     * 获取字符集。
     *
     * @return 字符集
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * 获取默认日期格式。
     *
     * @return 日期格式，可能为 {@code null}
     */
    public String getDateFormat() {
        return dateFormat;
    }

    /**
     * 获取模板模式主数据标签名。
     *
     * @return 主数据标签名
     */
    public String getTableTag() {
        return tableTag;
    }

    /**
     * 获取标签前缀。
     *
     * @return 标签前缀
     */
    public String getTagPrefix() {
        return tagPrefix;
    }

    /**
     * 获取标签后缀。
     *
     * @return 标签后缀
     */
    public String getTagSuffix() {
        return tagSuffix;
    }

    /**
     * 是否流式写出。
     *
     * @return 是否流式
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * 是否由框架关闭调用方传入的流。
     *
     * @return 是否自动关闭流
     */
    public boolean isAutoCloseStream() {
        return autoCloseStream;
    }

    /**
     * 以当前配置为基准派生新配置。
     *
     * @param builder 覆盖项
     * @return 新配置
     */
    public ExportConfig merge(Builder builder) {
        Builder merged = builder();
        merged.format = format;
        merged.engine = engine;
        merged.charset = charset;
        merged.dateFormat = dateFormat;
        merged.tableTag = tableTag;
        merged.tagPrefix = tagPrefix;
        merged.tagSuffix = tagSuffix;
        merged.streaming = streaming;
        merged.autoCloseStream = autoCloseStream;
        if (builder.format != null) {
            merged.format = builder.format;
        }
        if (builder.engine != null) {
            merged.engine = builder.engine;
        }
        if (builder.charset != null) {
            merged.charset = builder.charset;
        }
        if (builder.dateFormat != null) {
            merged.dateFormat = builder.dateFormat;
        }
        if (builder.tableTag != null) {
            merged.tableTag = builder.tableTag;
        }
        if (builder.tagPrefix != null) {
            merged.tagPrefix = builder.tagPrefix;
        }
        if (builder.tagSuffix != null) {
            merged.tagSuffix = builder.tagSuffix;
        }
        merged.streaming = merged.streaming || builder.streaming;
        merged.autoCloseStream = merged.autoCloseStream || builder.autoCloseStream;
        return new ExportConfig(merged);
    }

    /**
     * 导出配置构建器。
     */
    public static final class Builder {

        /**
         * 目标格式。
         */
        private String format;

        /**
         * 引擎名。
         */
        private String engine;

        /**
         * 字符集。
         */
        private Charset charset;

        /**
         * 默认日期格式。
         */
        private String dateFormat;

        /**
         * 主数据标签名。
         */
        private String tableTag;

        /**
         * 标签前缀。
         */
        private String tagPrefix;

        /**
         * 标签后缀。
         */
        private String tagSuffix;

        /**
         * 是否流式。
         */
        private boolean streaming;

        /**
         * 是否自动关闭流。
         */
        private boolean autoCloseStream;

        /**
         * 设置目标格式。
         *
         * @param value 格式标识
         * @return 当前构建器
         */
        public Builder format(String value) {
            this.format = ExportFormats.normalize(value);
            return this;
        }

        /**
         * 设置引擎名。
         *
         * @param value 引擎名
         * @return 当前构建器
         */
        public Builder engine(String value) {
            this.engine = value == null || value.isBlank() ? null : value;
            return this;
        }

        /**
         * 设置字符集。
         *
         * @param value 字符集
         * @return 当前构建器
         */
        public Builder charset(Charset value) {
            this.charset = value;
            return this;
        }

        /**
         * 设置默认日期格式。
         *
         * @param value 日期格式
         * @return 当前构建器
         */
        public Builder dateFormat(String value) {
            this.dateFormat = value == null || value.isBlank() ? null : value;
            return this;
        }

        /**
         * 设置模板主数据标签名。
         *
         * @param value 标签名
         * @return 当前构建器
         */
        public Builder tableTag(String value) {
            this.tableTag = value;
            return this;
        }

        /**
         * 设置标签前缀。
         *
         * @param value 前缀
         * @return 当前构建器
         */
        public Builder tagPrefix(String value) {
            this.tagPrefix = value;
            return this;
        }

        /**
         * 设置标签后缀。
         *
         * @param value 后缀
         * @return 当前构建器
         */
        public Builder tagSuffix(String value) {
            this.tagSuffix = value;
            return this;
        }

        /**
         * 设置是否流式写出。
         *
         * @param value 是否流式
         * @return 当前构建器
         */
        public Builder streaming(boolean value) {
            this.streaming = value;
            return this;
        }

        /**
         * 设置导出结束后是否由框架关闭调用方传入的目标流与模板流。
         *
         * @param value 是否自动关闭
         * @return 当前构建器
         */
        public Builder autoCloseStream(boolean value) {
            this.autoCloseStream = value;
            return this;
        }

        /**
         * 以另一个构建器为基准合并当前值（当前值优先）。
         *
         * @param other 基准构建器，可为 {@code null}
         * @return 当前构建器
         */
        public Builder mergeFrom(Builder other) {
            if (other == null) {
                return this;
            }
            if (format == null) {
                format = other.format;
            }
            if (engine == null) {
                engine = other.engine;
            }
            if (charset == null) {
                charset = other.charset;
            }
            if (dateFormat == null) {
                dateFormat = other.dateFormat;
            }
            if (tableTag == null) {
                tableTag = other.tableTag;
            }
            if (tagPrefix == null) {
                tagPrefix = other.tagPrefix;
            }
            if (tagSuffix == null) {
                tagSuffix = other.tagSuffix;
            }
            streaming = streaming || other.streaming;
            autoCloseStream = autoCloseStream || other.autoCloseStream;
            return this;
        }

        /**
         * 构建配置。
         *
         * @return 导出配置
         */
        public ExportConfig build() {
            return new ExportConfig(this);
        }
    }
}