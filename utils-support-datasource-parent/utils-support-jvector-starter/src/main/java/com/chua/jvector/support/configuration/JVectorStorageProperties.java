package com.chua.jvector.support.configuration;

/**
 * JVector 向量存储配置属性。
 *
 * @author CH
 * @since 2025/01/15
 */
public class JVectorStorageProperties {

    /**
     * 存储模式，默认 Larger-than-Memory。
     */
    private Mode mode = Mode.LARGER_THAN_MEMORY;

    /**
     * PQ 子空间数量（仅 LARGER_THAN_MEMORY 模式生效）。
     */
    private int pqSubspaces = 64;

    /**
     * 每个子空间的码本数量（仅 LARGER_THAN_MEMORY 模式生效）。
     */
    private int pqCentroidsPerSubspace = 256;

    /**
     * 图最大度数 M。
     */
    private int graphM = 32;

    /**
     * 构建时的搜索深度 efConstruction。
     */
    private int graphEfConstruction = 100;

    /**
     * 搜索时的候选集倍数 overquery。
     */
    private float searchOverquery = 2.0f;

    /**
     * 磁盘索引文件路径（ON_DISK 模式下使用）。
     */
    private String indexPath = "./jvector-index";

    /**
     * 是否预构建旋转矩阵和码本。
     */
    private boolean prepareOnStartup = true;

    /**
     * PQ 编码并行度；0 表示使用 {@link ForkJoinPool#commonPool()}。
     */
    private int pqParallelism = 0;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int getPqSubspaces() {
        return pqSubspaces;
    }

    public void setPqSubspaces(int pqSubspaces) {
        this.pqSubspaces = pqSubspaces;
    }

    public int getPqCentroidsPerSubspace() {
        return pqCentroidsPerSubspace;
    }

    public void setPqCentroidsPerSubspace(int pqCentroidsPerSubspace) {
        this.pqCentroidsPerSubspace = pqCentroidsPerSubspace;
    }

    public int getGraphM() {
        return graphM;
    }

    public void setGraphM(int graphM) {
        this.graphM = graphM;
    }

    public int getGraphEfConstruction() {
        return graphEfConstruction;
    }

    public void setGraphEfConstruction(int graphEfConstruction) {
        this.graphEfConstruction = graphEfConstruction;
    }

    public float getSearchOverquery() {
        return searchOverquery;
    }

    public void setSearchOverquery(float searchOverquery) {
        this.searchOverquery = searchOverquery;
    }

    public String getIndexPath() {
        return indexPath;
    }

    public void setIndexPath(String indexPath) {
        this.indexPath = indexPath;
    }

    public boolean isPrepareOnStartup() {
        return prepareOnStartup;
    }

    public void setPrepareOnStartup(boolean prepareOnStartup) {
        this.prepareOnStartup = prepareOnStartup;
    }

    /**
     * JVector 存储模式。
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
