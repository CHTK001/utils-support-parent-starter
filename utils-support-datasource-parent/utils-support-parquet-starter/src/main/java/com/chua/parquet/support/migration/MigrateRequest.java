package com.chua.parquet.support.migration;

/**
 * 数据迁移请求，通过 {@link #builder()} 构建，构建时校验必填项。
 *
 * <p>必填：{@code sourceType}、{@code targetType}（存储类型，如 {@code parquet}、{@code rdbms}）、
 * {@code sourceTable}、{@code targetTable}、{@code batchSize}（必须大于 0，默认 {@value #DEFAULT_BATCH_SIZE}）。</p>
 *
 * <p>可选：{@code sourceEngine} / {@code targetEngine} 指定源/目标 Engine Bean 名称，
 * 缺省时按存储类型名解析（如 {@code parquet}）；RDBMS 侧由插件内 {@code JdbcTemplate} 支撑，无需 Engine。</p>
 *
 * <p>可选：{@code migratorName} 显式指定迁移插件；缺省时按 {@code sourceType + "->" + targetType} 推导。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class MigrateRequest {

    /** 默认批次大小 */
    public static final int DEFAULT_BATCH_SIZE = 500;

    private final String sourceType;
    private final String targetType;
    private final String sourceEngine;
    private final String targetEngine;
    private final String sourceTable;
    private final String targetTable;
    private final int batchSize;
    private final String migratorName;

    private MigrateRequest(Builder builder) {
        this.sourceType = builder.sourceType.trim();
        this.targetType = builder.targetType.trim();
        this.sourceEngine = builder.sourceEngine;
        this.targetEngine = builder.targetEngine;
        this.sourceTable = builder.sourceTable.trim();
        this.targetTable = builder.targetTable.trim();
        this.batchSize = builder.batchSize;
        this.migratorName = builder.migratorName;
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
     * 源端存储类型，如 {@code parquet}、{@code rdbms}。
     *
     * @return 源端存储类型
     */
    public String getSourceType() {
        return sourceType;
    }

    /**
     * 目标端存储类型。
     *
     * @return 目标端存储类型
     */
    public String getTargetType() {
        return targetType;
    }

    /**
     * 源端 Engine Bean 名称（可选）。
     *
     * @return 源端 Engine Bean 名称，可能为 {@code null}
     */
    public String getSourceEngine() {
        return sourceEngine;
    }

    /**
     * 目标端 Engine Bean 名称（可选）。
     *
     * @return 目标端 Engine Bean 名称，可能为 {@code null}
     */
    public String getTargetEngine() {
        return targetEngine;
    }

    /**
     * 源表名。
     *
     * @return 源表名
     */
    public String getSourceTable() {
        return sourceTable;
    }

    /**
     * 目标表名。
     *
     * @return 目标表名
     */
    public String getTargetTable() {
        return targetTable;
    }

    /**
     * 每批行数，恒大于 0。
     *
     * @return 每批行数
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 显式指定的迁移插件名（可选）。
     *
     * @return 迁移插件名，可能为 {@code null}
     */
    public String getMigratorName() {
        return migratorName;
    }

    /**
     * 实际用于解析源端 Engine 的 Bean 名称：未显式指定时取 {@code sourceType}。
     *
     * @return 源端 Engine Bean 名称
     */
    public String effectiveSourceEngine() {
        return isBlank(sourceEngine) ? sourceType : sourceEngine.trim();
    }

    /**
     * 实际用于解析目标端 Engine 的 Bean 名称：未显式指定时取 {@code targetType}。
     *
     * @return 目标端 Engine Bean 名称
     */
    public String effectiveTargetEngine() {
        return isBlank(targetEngine) ? targetType : targetEngine.trim();
    }

    /**
     * 按存储类型推导的默认插件名：{@code sourceType + "->" + targetType}。
     *
     * @return 默认插件名
     */
    public String pluginKey() {
        return sourceType + "->" + targetType;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "MigrateRequest{source=" + sourceType + ":" + sourceTable
                + ", target=" + targetType + ":" + targetTable
                + ", batchSize=" + batchSize
                + (migratorName == null ? "" : ", migrator=" + migratorName) + '}';
    }

    /**
     * 迁移请求构建器。
     */
    public static final class Builder {

        private String sourceType;
        private String targetType;
        private String sourceEngine;
        private String targetEngine;
        private String sourceTable;
        private String targetTable;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private String migratorName;

        /**
         * 设置源端存储类型，必填。
         *
         * @param sourceType 源端存储类型
         * @return this
         */
        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        /**
         * 设置目标端存储类型，必填。
         *
         * @param targetType 目标端存储类型
         * @return this
         */
        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }

        /**
         * 设置源端 Engine Bean 名称，可选，缺省取 {@code sourceType}。
         *
         * @param sourceEngine 源端 Engine Bean 名称
         * @return this
         */
        public Builder sourceEngine(String sourceEngine) {
            this.sourceEngine = sourceEngine;
            return this;
        }

        /**
         * 设置目标端 Engine Bean 名称，可选，缺省取 {@code targetType}。
         *
         * @param targetEngine 目标端 Engine Bean 名称
         * @return this
         */
        public Builder targetEngine(String targetEngine) {
            this.targetEngine = targetEngine;
            return this;
        }

        /**
         * 设置源表名，必填。
         *
         * @param sourceTable 源表名
         * @return this
         */
        public Builder sourceTable(String sourceTable) {
            this.sourceTable = sourceTable;
            return this;
        }

        /**
         * 设置目标表名，必填。
         *
         * @param targetTable 目标表名
         * @return this
         */
        public Builder targetTable(String targetTable) {
            this.targetTable = targetTable;
            return this;
        }

        /**
         * 设置每批行数，默认 {@value #DEFAULT_BATCH_SIZE}，必须大于 0。
         *
         * @param batchSize 每批行数
         * @return this
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * 设置显式迁移插件名，可选；缺省按 {@code sourceType->targetType} 推导。
         *
         * @param migratorName 迁移插件名
         * @return this
         */
        public Builder migratorName(String migratorName) {
            this.migratorName = migratorName;
            return this;
        }

        /**
         * 构建并校验迁移请求。
         *
         * @return 迁移请求
         * @throws IllegalArgumentException 必填项缺失或 batchSize 非法时抛出
         */
        public MigrateRequest build() {
            if (isBlank(sourceType)) {
                throw new IllegalArgumentException("sourceType 不能为空");
            }
            if (isBlank(targetType)) {
                throw new IllegalArgumentException("targetType 不能为空");
            }
            if (isBlank(sourceTable)) {
                throw new IllegalArgumentException("sourceTable 不能为空");
            }
            if (isBlank(targetTable)) {
                throw new IllegalArgumentException("targetTable 不能为空");
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize 必须大于 0，实际为: " + batchSize);
            }
            return new MigrateRequest(this);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
