package com.chua.vector.support.configuration;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 向量存储统一配置属性，支持 cuvs (GPU) 和 jvector (CPU) 双后端。
 *
 * <p>通过 {@link com.chua.common.support.vector.VectorStorageProvider} 链式构建器传入，
 * 或绑定到 Spring Boot {@code application.yml}。</p>
 *
 * <pre>{@code
 * // 自动检测（推荐）：有 GPU 用 cuVS，无 GPU 降级到 jvector
 * VectorStorage storage = VectorStorageProvider.of("vector")
 *         .dimension(768).algorithm("cosine")
 *         .properties(new VectorStorageProperties())
 *         .build();
 *
 * // 强制使用 CPU（无 GPU 环境或禁用 GPU）
 * VectorStorage storage = VectorStorageProvider.of("vector")
 *         .dimension(768)
 *         .properties(new VectorStorageProperties().forceCpu(true))
 *         .build();
 *
 * // 强制使用 GPU（无 GPU 时抛出异常而非降级）
 * VectorStorage storage = VectorStorageProvider.of("vector")
 *         .properties(new VectorStorageProperties()
 *                 .forceCpu(false)
 *                 .requireGpu(true))
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@Accessors(chain = true, fluent = true)
public class VectorStorageProperties implements Serializable {

    private static final long serialVersionUID = 1L; // 串行版本uid

    // ---- 后端选择 ----

    /**
     * 后端类型：cuvs / jvector / auto（自动检测，优先 GPU）。
     * <p>通常通过 {@link #forceCpu} 和 {@link #requireGpu} 控制，显式设置此字段可强制指定后端。</p>
     */
    private Backend backend = Backend.AUTO;

    /**
     * 是否强制使用 CPU（jvector）。
     * <p>true 时跳过 GPU 检测，直接使用 jvector；false 时自动检测 GPU。</p>
     */
    private boolean forceCpu = false;

    /**
     * 是否要求 GPU 必须可用。
     * <p>true 时若 GPU 不可用则直接抛异常（不降级）；false 时自动降级到 CPU。</p>
     */
    private boolean requireGpu = false;

 // ---- cuvs GPU 参数 ----

    /**
     * CUDA 设备 标识，默认 0。
     */
    private int deviceId = 0;

    /**
     * cuvs 索引类型，默认 CAGRA。
     */
    private CuvsIndexType indexType = CuvsIndexType.CAGRA;

    /**
     * CAGRA/HNSW 图度（输出 图计算 学位），默认 64。
     */
    private int graphDegree = 64;

    /**
     * CAGRA 中间图度（intermediate 图计算 学位），默认 128。
     */
    private int intermediateGraphDegree = 128;

    /**
     * 搜索时的 exploration factor（搜索广度），默认 100。
     */
    private int searchEf = 100;

    /**
     * bruteforce 模式下额外取候选倍数（内部使用，搜索时多取）。
     */
    private int bruteForceFetchFactor = 3;

    // ---- jvector CPU 参数 ----

    /**
     * jvector 存储模式，默认 内存。
     */
    private JvectorMode jvectorMode = JvectorMode.MEMORY;

    /**
     * jvector 图最大度数 M，默认 32。
     */
    private int jvectorGraphM = 32;

    /**
     * jvector 建图时搜索深度 efconstruction，默认 100。
     */
    private int jvectorEfConstruction = 100;

    /**
     * jvector 磁盘索引路径（ON_DISK / LARGER_THAN_内存 模式）。
     */
    private String jvectorIndexPath = "./vector-index";

    /**
     * 后端类型枚举。
     * @author CH
     * @since 4.0.0
     */
    public enum Backend {
        /**
         * 使用 NVIDIA cuvs GPU 加速。
         */
        CUVS,
        /**
         * 使用 jvector CPU 实现。
         */
        JVECTOR,
        /**
         * 自动检测：有 cuvs NAT 库则用 GPU，否则用 jvector。
         */
        AUTO
    }

    /**
     * cuvs 支持的索引类型。
     * @author CH
     * @since 4.0.0
     */
    public enum CuvsIndexType {
        /**
         * CAGRA：GPU 图索引，召回率和吞吐平衡最佳（推荐）。
         */
        CAGRA,
        /**
         * bruteforce：精确暴力搜索，适合小数据集或验证基准。
         */
        BRUTE_FORCE,
        /**
         * HNSW：GPU 加速的 HNSW 图索引。
         */
        HNSW
    }

    /**
     * jvector 存储模式。
     * @author CH
     * @since 4.0.0
     */
    public enum JvectorMode {
        /**
         * 纯内存图，适合小数据集或测试。
         */
        MEMORY,
        /**
         * 磁盘持久化图，内存中保留上层图。
         */
        ON_DISK,
        /**
         * 超内存模式，PQ 压缩向量驻留内存。
         */
        LARGER_THAN_MEMORY
    }
}
