package com.chua.common.support.vector;

import com.chua.common.support.spi.ServiceProvider;

/**
 * 向量存储链式构建器。
 * <p>
 * 支持链式配置维度、算法、连接信息等参数，最终调用 {@link #build()} 创建实例。
 * 第三方可通过 {@link #algorithm(VectorCompareAlgorithm)} 注入自定义比较算法。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public class VectorStorageBuilder {

    /**
      * 存储类型，默认 内存。
     */
    private String type = "MEMORY";

    /**
     * 向量维度，默认 128。
     */
    private int dimension = 128;

    /**
     * 比较算法，默认欧几里得。
     */
    private VectorCompareAlgorithm algorithm;

    /**
     * 服务主机地址。
     */
    private String host = "localhost";

    /**
     * 服务端口。
     */
    private int port;

    /**
     * 认证令牌。
     */
    private String token;

    /**
      * Milvus 集合 名称，仅 MILVUS 类型生效。
     */
    private String collection = "vector_store";

    /**
      * cuvs/jvector 向量存储配置属性，仅 向量 类型生效。
     */
    private Object vectorProperties;

    /**
     * 创建构建器实例。
     *
     * @return 构建器
     */
    public static VectorStorageBuilder newBuilder() {
        return new VectorStorageBuilder();
    }

    /**
     * 设置存储类型。
     *
     * @param type 存储类型（内存 / MILVUS / JVECTOR / 向量）
     * @return this
     */
    public VectorStorageBuilder type(String type) {
        this.type = type;
        return this;
    }

    /**
     * 设置向量维度。
     *
     * @param dimension 向量维度
     * @return this
     */
    public VectorStorageBuilder dimension(int dimension) {
        this.dimension = dimension;
        return this;
    }

    /**
     * 设置自定义比较算法。
     *
     * @param algorithm 自定义算法实例
     * @return this
     */
    public VectorStorageBuilder algorithm(VectorCompareAlgorithm algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    /**
     * 通过名称设置内置比较算法。
     *
     * @param name 算法名称（EUCLIDEAN / COSINE / DOT）
     * @return this
     */
    public VectorStorageBuilder algorithm(String name) {
        this.algorithm = switch (name.toUpperCase()) {
            case "EUCLIDEAN" -> VectorCompareAlgorithm.euclidean();
            case "COSINE" -> VectorCompareAlgorithm.cosine();
            case "DOT" -> VectorCompareAlgorithm.dotProduct();
            default -> throw new IllegalArgumentException("不支持的算法: " + name);
        };
        return this;
    }

    /**
     * 设置服务主机地址。
     *
     * @param host 主机地址
     * @return this
     */
    public VectorStorageBuilder host(String host) {
        this.host = host;
        return this;
    }

    /**
     * 设置服务端口。
     *
     * @param port 端口号
     * @return this
     */
    public VectorStorageBuilder port(int port) {
        this.port = port;
        return this;
    }

    /**
     * 设置认证令牌。
     *
     * @param token 认证令牌
     * @return this
     */
    public VectorStorageBuilder token(String token) {
        this.token = token;
        return this;
    }

    /**
      * 设置 MILVUS 集合 名称。
     *
     * @param collection 集合 名称
     * @return this
     */
    public VectorStorageBuilder collection(String collection) {
        this.collection = collection;
        return this;
    }

    /**
      * 设置向量存储配置属性（cuvs/jvector），仅 向量 类型生效。
     *
     * @param properties 配置对象（如 {@code VectorStorageProperties}）
     * @return this
     */
    public VectorStorageBuilder properties(Object properties) {
        this.vectorProperties = properties;
        return this;
    }

    /**
     * 构建向量存储实例。
     *
     * @return 向量存储实例
     */
    public VectorStorage build() {
        var algo = algorithm != null ? algorithm : VectorCompareAlgorithm.euclidean();
        return switch (type.toUpperCase()) {
            case "MEMORY" -> VectorStorageProvider.create("memory", dimension, algo);
            case "JVECTOR" -> createOptionalStorage("jvector", dimension, algo, vectorProperties);
            case "MILVUS" -> createOptionalStorage("milvus", dimension, algo, vectorProperties);
            case "VECTOR" -> VectorStorageProvider.create("vector", dimension, algo, vectorProperties);
            default -> throw new IllegalArgumentException("不支持的向量存储类型: " + type);
        };
    }

    /**
      * 通过 服务提供者 SPI 创建可选模块（jvector/milvus）的向量存储。
     *
     * @param spiName    SPI 扩展名
     * @param dimension  向量维度
     * @param algo       比较算法
     * @param properties 实现特定配置
     * @return 向量存储实例
     */
    private VectorStorage createOptionalStorage(String spiName,
                                                int dimension,
                                                VectorCompareAlgorithm algo,
                                                Object properties) {
        var provider = ServiceProvider.of(VectorStorageProvider.class).getExtension(spiName);
        if (provider == null) {
            throw new RuntimeException(spiName + " 模块未加载，请引入对应依赖并确认 SPI 已注册");
        }
        return provider.create(dimension, algo, properties);
    }
}
