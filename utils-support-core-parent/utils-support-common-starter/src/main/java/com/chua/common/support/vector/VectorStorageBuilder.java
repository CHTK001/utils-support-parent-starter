package com.chua.common.support.vector;

import com.chua.common.support.spi.ServiceProvider;

/**
 * 向量存储链式构建器。
 * <p>
 * 支持链式配置维度、算法、连接信息等参数，最终调用 {@link #build()} 创建实例。
 * 第三方可通过 {@link #algorithm(VectorCompareAlgorithm)} 注入自定义比较算法。
 * </p>
 *
 * <p>存储类型即 {@link VectorStorageProvider} 的 SPI 名称，由 {@link ServiceProvider}
 * 从 {@code META-INF/extensions} 注册表解析，本类不硬编码任何具体实现。</p>
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
    // [P3C A20 豁免]
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
     * 向量存储配置属性（各实现自有类型：cuvs / jvector / zvec 等），
     * 仅 JVECTOR / ZVEC / VECTOR 类型生效。
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
     * @param type 已注册的向量存储 SPI 名称（如 MEMORY / JVECTOR / ZVEC / MILVUS / VECTOR）
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
     * 设置向量存储配置属性（各实现自有类型，如 cuvs / jvector / zvec），
     * 原样传给最终解析到的 SPI 实现。
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
     * <p>不再按类型写死分支：{@link #type} 直接作为 SPI 名称交给
     * {@link ServiceProvider} 解析，新增向量库只需引入对应 starter 并完成 SPI 注册，
     * 本类无需改动。</p>
     *
     * @return 向量存储实例
     * @throws IllegalArgumentException type 未注册时
     */
    public VectorStorage build() {
        var algo = algorithm != null ? algorithm : VectorCompareAlgorithm.euclidean();
        var provider = ServiceProvider.of(VectorStorageProvider.class).getExtension(type);
        if (provider == null) {
            throw new IllegalArgumentException("未注册的向量存储类型: " + type
                    + "，已注册: " + VectorStorageProvider.providers()
                    + "；请检查名称或引入对应 starter");
        }
        return provider.create(dimension, algo, vectorProperties);
    }
}
