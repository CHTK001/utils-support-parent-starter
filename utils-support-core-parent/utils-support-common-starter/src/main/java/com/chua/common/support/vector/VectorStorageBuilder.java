package com.chua.common.support.vector;


import com.chua.common.support.reflection.ReflectUtils;
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
     * 存储类型，默认 MEMORY。
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
     * Milvus collection 名称，仅 MILVUS 类型生效。
     */
    private String collection = "vector_store";

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
     * @param type 存储类型（MEMORY / MILVUS / JVECTOR）
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
     * 设置 MILVUS collection 名称。
     *
     * @param collection collection 名称
     * @return this
     */
    public VectorStorageBuilder collection(String collection) {
        this.collection = collection;
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
            case "MEMORY" -> new MemoryVectorStorage(dimension, algo);
            case "JVECTOR" -> {
                try {
                    var cls = ReflectUtils.forName("com.chua.jvector.support.storage.JVectorVectorStorage");
                    var ctor = cls.getConstructor(int.class, VectorCompareAlgorithm.class);
                    yield (VectorStorage) ctor.newInstance(dimension, algo);
                } catch (Exception e) {
                    throw new RuntimeException("JVECTOR 模块未加载: " + e.getMessage());
                }
            }
            case "MILVUS" -> {
                try {
                    var cls = ReflectUtils.forName("com.chua.milvus.support.storage.MilvusVectorStorage");
                    var ctor = cls.getConstructor(int.class, VectorCompareAlgorithm.class, String.class, int.class, String.class);
                    yield (VectorStorage) ctor.newInstance(dimension, algo, host, port, collection);
                } catch (Exception e) {
                    throw new RuntimeException("MILVUS 模块未加载: " + e.getMessage());
                }
            }
            default -> throw new IllegalArgumentException("不支持的向量存储类型: " + type);
        };
    }
}
