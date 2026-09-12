package com.chua.jvector.support.configuration;

import lombok.Data;

import java.util.concurrent.ForkJoinPool;

/**
   * j向量 向量存储配置属性。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class JVectorStorageProperties {

    /**
     * 默认 PQ 子空间数量
     */
    private static final int DEFAULT_PQ_SUBSPACES = 64;

    /**
     * 默认每个子空间的码本数量
     */
    private static final int DEFAULT_PQ_CENTROIDS = 256;

    /**
     * 默认图最大度数
     */
    private static final int DEFAULT_GRAPH_M = 32;

    /**
     * 默认构建搜索深度
     */
    private static final int DEFAULT_GRAPH_EF_CONSTRUCTION = 100;

    /**
     * 默认搜索倍数
     */
    private static final float DEFAULT_SEARCH_OVERQUERY = 2.0f;

    /**
     * 默认索引文件路径
     */
    private static final String DEFAULT_INDEX_PATH = "./jvector-index";

    /**
      * 存储模式，默认 Larger-than-内存。
     */
    private Mode mode = Mode.LARGER_THAN_MEMORY;

    /**
      * PQ 子空间数量（仅 LARGER_THAN_内存 模式生效）。
     */
    private int pqSubspaces = DEFAULT_PQ_SUBSPACES;

    /**
      * 每个子空间的码本数量（仅 LARGER_THAN_内存 模式生效）。
     */
    private int pqCentroidsPerSubspace = DEFAULT_PQ_CENTROIDS;

    /**
     * 图最大度数 M。
     */
    private int graphM = DEFAULT_GRAPH_M;

    /**
      * 构建时的搜索深度 efconstruction。
     */
    private int graphEfConstruction = DEFAULT_GRAPH_EF_CONSTRUCTION;

    /**
     * 搜索时的候选集倍数 overquery。
     */
    private float searchOverquery = DEFAULT_SEARCH_OVERQUERY;

    /**
     * 磁盘索引文件路径（ON_DISK 模式下使用）。
     */
    private String indexPath = DEFAULT_INDEX_PATH;

    /**
     * 是否预构建旋转矩阵和码本。
     */
    private boolean prepareOnStartup = true;

    /**
     * PQ 编码并行度；0 表示使用 {@link ForkJoinPool#commonPool()}。
     */
    private int pqParallelism = 0;

    /**
      * j向量 存储模式。
     * @author CH
     * @since 4.0.0
     */
    public enum Mode {
        /**
         * 纯内存图，适合小数据集或测试。
         */
        MEMORY,

        /**
         * 磁盘持久化图，内存中保留上层图，底层在磁盘。
         */
        ON_DISK,

        /**
         * 超内存模式，PQ 压缩向量驻留内存，全精度向量在磁盘。
         */
        LARGER_THAN_MEMORY
    }
}
