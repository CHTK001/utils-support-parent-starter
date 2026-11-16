package com.chua.zvec.support.configuration;

import lombok.Data;

/**
 * Zvec 向量存储配置属性。
 *
 * <p>对应 SPI 配置表单声明的键：{@code path} / {@code hnswM} / {@code efConstruction} /
 * {@code queryEf} / {@code enableMmap} / {@code flushOnWrite}，缺项一律取本类默认值。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
public class ZvecStorageProperties {

    /**
     * 默认集合目录。
     */
    private static final String DEFAULT_PATH = "./zvec-index";

    /**
     * 默认 HNSW 图最大度数 M。
     */
    private static final int DEFAULT_HNSW_M = 16;

    /**
     * 默认 HNSW 构建搜索深度 efConstruction。
     */
    private static final int DEFAULT_EF_CONSTRUCTION = 200;

    /**
     * 默认查询搜索深度 ef（topK 大于该值时按 topK 取值）。
     */
    private static final int DEFAULT_QUERY_EF = 64;

    /**
     * 集合目录：zvec 一个目录即一个集合，目录名由业务按知识库维度分配。
     */
    private String path = DEFAULT_PATH;

    /**
     * HNSW 图最大度数 M，越大召回越高、内存与构建开销越大。
     */
    private int hnswM = DEFAULT_HNSW_M;

    /**
     * HNSW 构建搜索深度 efConstruction。
     */
    private int efConstruction = DEFAULT_EF_CONSTRUCTION;

    /**
     * HNSW 查询搜索深度 ef；≤0 时取 {@link #DEFAULT_QUERY_EF} 与 topK 的较大值。
     */
    private int queryEf = DEFAULT_QUERY_EF;

    /**
     * 是否对集合启用 mmap 读取。
     */
    private boolean enableMmap;

    /**
     * 是否每次写入后立即 flush。
     *
     * <p>实测 128 维、单条写入场景下 flush 约 20ms/次，批量灌库时会把写入放大两个数量级，
     * 因此默认关闭；关闭时数据在 {@code close()} / {@code rebuild()} 时统一落盘，
     * 进程被强杀可能丢失最后一次 flush 之后的增量。</p>
     */
    private boolean flushOnWrite;
}
