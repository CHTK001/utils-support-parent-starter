package com.chua.datasource.support.middleware;

/**
 * 树节点：顶层节点（库 / Bucket / Topic）与子节点（集合 / 度量 / 分区）共用。
 *
 * <p>实现方按自己的中间件语义填字段即可：Kafka 的顶层是 Topic、子节点是 Partition；
 * InfluxDB 的顶层是 Bucket、子节点是 Measurement。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareTreeNode {

    /** 节点标识，用于展开子节点与查询定位，同级内唯一 */
    private final String key;

    /** 展示名 */
    private final String label;

    /** 附加说明（副本数、保留期、键数量等），可为空 */
    private final String comment;

    /** 预估条目数（文档数 / 消息数），未知时为 {@code null} */
    private final Long estimatedCount;

    /**
     * 全字段构造。
     *
     * @param key            节点标识
     * @param label          展示名
     * @param comment        附加说明
     * @param estimatedCount 预估条目数
     */
    public MiddlewareTreeNode(String key, String label, String comment, Long estimatedCount) {
        this.key = key;
        this.label = label;
        this.comment = comment;
        this.estimatedCount = estimatedCount;
    }

    /**
     * 紧凑构造：只有标识与展示名。
     *
     * @param key   节点标识
     * @param label 展示名
     * @return 节点对象
     */
    public static MiddlewareTreeNode of(String key, String label) {
        return new MiddlewareTreeNode(key, label, "", null);
    }

    /**
     * 带条目数的构造。
     *
     * @param key            节点标识
     * @param label          展示名
     * @param estimatedCount 预估条目数
     * @return 节点对象
     */
    public static MiddlewareTreeNode of(String key, String label, Long estimatedCount) {
        return new MiddlewareTreeNode(key, label, "", estimatedCount);
    }

    /**
     * @return 节点标识
     */
    public String key() {
        return key == null ? "" : key;
    }

    /**
     * @return 展示名
     */
    public String label() {
        return label == null ? "" : label;
    }

    /**
     * @return 附加说明，无则为空串
     */
    public String comment() {
        return comment == null ? "" : comment;
    }

    /**
     * @return 预估条目数，未知时为 {@code null}
     */
    public Long estimatedCount() {
        return estimatedCount;
    }

    @Override
    public String toString() {
        return "MiddlewareTreeNode{key=" + key() + ", label=" + label() + ", count=" + estimatedCount + '}';
    }

    // ==== Jackson getters (generated) ====
    // 本类用 fluent 访问器（xxx()）对外，Jackson 默认只认 getXxx()/isXxx()，
    // 因此这里补一层 getter 别名；既有调用方不受影响。

    /**
     * Jackson getter，对应 {@link #key()}。
     *
     * @return key
     */
    public String getKey() {
        return key();
    }

    /**
     * Jackson getter，对应 {@link #label()}。
     *
     * @return label
     */
    public String getLabel() {
        return label();
    }

    /**
     * Jackson getter，对应 {@link #comment()}。
     *
     * @return comment
     */
    public String getComment() {
        return comment();
    }

    /**
     * Jackson getter，对应 {@link #estimatedCount()}。
     *
     * @return estimatedCount
     */
    public Long getEstimatedCount() {
        return estimatedCount();
    }
}
