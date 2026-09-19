package com.chua.common.support.vector;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 向量存储提供者 SPI。
 *
 * <p>通过 SPI 注册多个实现（内存、JVector、Milvus 等），可按名称获取。</p>
 *
 * <pre>{@code
 * // 链式风格（推荐）：
 * VectorStorage storage = VectorStorageProvider.of("jvector")
 *         .dimension(128)
 *         .algorithm("cosine")
 *         .properties(props)
 *         .build();
 *
 * // 传统风格：
 * VectorStorage storage = VectorStorageProvider.create("jvector", dim, algo, properties);
 * VectorStorage storage = VectorStorageProvider.create("memory", dim, algo);
 *
 * // 列出所有 SPI 实现：
 * List<String> providers = VectorStorageProvider.providers();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VectorStorageProvider {

    /**
     * SPI 名称（如 "memory", "jvector", "milvus"）。
     * @return 结果字符串
     */
    String name();

    /**
     * 创建向量存储实例。
     *
     * @param dimension  向量维度
     * @param algorithm  比较算法
     * @param properties 实现特定的配置（可为 null）
     * @return VectorStorage 实例
     */
    VectorStorage create(int dimension, VectorCompareAlgorithm algorithm, Object properties);

    /**
     * 链式构建入口：通过 SPI 名称获取构建器。
     *
     * <pre>{@code
     * VectorStorage storage = VectorStorageProvider.of("jvector")
     *         .dimension(128)
     *         .algorithm("cosine")
     *         .properties(props)
     *         .build();
     * }</pre>
     *
     * @param providerName SPI 名称（如 "memory", "jvector", "milvus"）
     * @return 链式构建器
     */
    static Builder of(String providerName) {
        var provider = ServiceProvider.of(VectorStorageProvider.class).getExtension(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("未找到向量存储 SPI: " + providerName);
        }
        return new Builder(provider);
    }

    /**
     * 创建指定 SPI 名称的向量存储。
     *
     * @param providerName SPI 名称
     * @param dimension    向量维度
     * @param algorithm    比较算法
     * @return VectorStorage 实例
     */
    static VectorStorage create(String providerName, int dimension, VectorCompareAlgorithm algorithm) {
        return create(providerName, dimension, algorithm, null);
    }

    /**
     * 创建指定 SPI 名称的向量存储。
     *
     * @param providerName SPI 名称
     * @param dimension    向量维度
     * @param algorithm    比较算法
     * @param properties   实现特定的配置（可为 null）
     * @return VectorStorage 实例
     */
    static VectorStorage create(String providerName,
                                int dimension,
                                VectorCompareAlgorithm algorithm,
                                Object properties) {
        try {
            return ServiceProvider.of(VectorStorageProvider.class)
                    .getExtension(providerName)
                    .create(dimension, algorithm, properties);
        } catch (Exception e) {
            throw new RuntimeException(
                    "无法创建向量存储 [" + providerName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 返回所有已注册的 SPI 名称。
     * @return 结果列表，无数据时为空列表
     */
    static List<String> providers() {
        return ServiceProvider.of(VectorStorageProvider.class)
                .getExtensions()
                .stream()
                .toList();
    }

    /**
     * 链式构建器，通过 {@link #of(String)} 获取。
     *
     * <pre>{@code
     * VectorStorage storage = VectorStorageProvider.of("jvector")
     *         .dimension(128)
     *         .algorithm("cosine")
     *         .properties(props)
     *         .build();
     * }</pre>
     */
    final class Builder {
        /** 提供者 */
        private final VectorStorageProvider provider;
        /** Dimension */
        private int dimension = 128;
        /** 算法 */
        private VectorCompareAlgorithm algorithm = VectorCompareAlgorithm.euclidean();
        /** 属性 */
        private Object properties;

        Builder(VectorStorageProvider provider) {
            this.provider = provider;
        }

        /**
         * 设置向量维度。
         *
         * @param dimension 向量维度
         * @return this
         */
        public Builder dimension(int dimension) {
            this.dimension = dimension;
            return this;
        }

        /**
         * 设置自定义比较算法。
         *
         * @param algorithm 比较算法实例
         * @return this
         */
        public Builder algorithm(VectorCompareAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        /**
         * 通过名称设置内置比较算法。
         *
         * @param name 算法名称（EUCLIDEAN / COSINE / DOT）
         * @return this
         */
        public Builder algorithm(String name) {
            this.algorithm = switch (name.toUpperCase()) {
                case "EUCLIDEAN" -> VectorCompareAlgorithm.euclidean();
                case "COSINE" -> VectorCompareAlgorithm.cosine();
                case "DOT" -> VectorCompareAlgorithm.dotProduct();
                default -> throw new IllegalArgumentException("不支持的算法: " + name);
            };
            return this;
        }

        /**
         * 设置实现特定的配置属性。
         *
         * @param properties 配置对象（可为 null）
         * @return this
         */
        public Builder properties(Object properties) {
            this.properties = properties;
            return this;
        }

        /**
         * 构建向量存储实例。
         *
         * @return VectorStorage 实例
         */
        public VectorStorage build() {
            return provider.create(dimension, algorithm, properties);
        }
    }

    /**
     * 默认的内存实现（最低优先级，作为兜底）。
     */
    @Spi(value = "memory", order = -100)
    class MemoryProvider implements VectorStorageProvider {
        @Override
        /** Name */
        public String name() {
            return "memory";
        }

        @Override
        /**
        * 创建
        * @param dimension dimension
        * @param algorithm algorithm
        * @param properties properties
        */
        public VectorStorage create(int dimension,
                                    VectorCompareAlgorithm algorithm,
                                    Object properties) {
            return new MemoryVectorStorage(dimension, algorithm);
        }
    }
}
