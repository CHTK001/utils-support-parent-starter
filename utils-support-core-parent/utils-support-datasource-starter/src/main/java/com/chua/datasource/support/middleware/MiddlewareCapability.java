package com.chua.datasource.support.middleware;

import java.util.List;

/**
 * 中间件能力声明：实现方用它告诉宿主「我支持哪些操作」。
 *
 * <p>宿主据此渲染界面，而不是把 Kafka 的 Topic、InfluxDB 的 Bucket 这些概念写死。
 * 新增中间件时只要这里声明清楚，前端与 Controller 无需改动。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareCapability {

    /** 是否支持「列出顶层节点」（库 / Bucket / Topic） */
    private final boolean topNodeSupported;

    /** 是否支持「展开子节点」（集合 / 度量 / 分区） */
    private final boolean childNodeSupported;

    /** 是否支持「执行只读查询」 */
    private final boolean querySupported;

    /** 是否支持「查看单条详情」 */
    private final boolean documentSupported;

    /** 顶层节点在界面上的称呼，如 {@code 逻辑库} / {@code 存储桶} / {@code 主题} */
    private final String topNodeLabel;

    /** 子节点在界面上的称呼，如 {@code 集合} / {@code 度量} / {@code 分区} */
    private final String childNodeLabel;

    /** 查询输入框的占位提示 */
    private final String queryHint;

    /** 查询语法说明（界面上的提示文案） */
    private final String queryDescription;

    /**
     * 全能力构造。
     *
     * @param topNodeLabel     顶层节点称呼
     * @param childNodeLabel   子节点称呼
     * @param queryHint        查询占位提示
     * @param queryDescription 查询语法说明
     */
    public MiddlewareCapability(String topNodeLabel, String childNodeLabel,
                                String queryHint, String queryDescription) {
        this(true, true, true, true, topNodeLabel, childNodeLabel, queryHint, queryDescription);
    }

    /**
     * 全字段构造：供「只读浏览、不支持查询」的中间件使用。
     *
     * @param topNodeSupported   是否支持顶层节点
     * @param childNodeSupported 是否支持子节点
     * @param querySupported     是否支持查询
     * @param documentSupported  是否支持详情
     * @param topNodeLabel       顶层节点称呼
     * @param childNodeLabel     子节点称呼
     * @param queryHint          查询占位提示
     * @param queryDescription   查询语法说明
     */
    public MiddlewareCapability(boolean topNodeSupported, boolean childNodeSupported,
                                boolean querySupported, boolean documentSupported,
                                String topNodeLabel, String childNodeLabel,
                                String queryHint, String queryDescription) {
        this.topNodeSupported = topNodeSupported;
        this.childNodeSupported = childNodeSupported;
        this.querySupported = querySupported;
        this.documentSupported = documentSupported;
        this.topNodeLabel = topNodeLabel;
        this.childNodeLabel = childNodeLabel;
        this.queryHint = queryHint;
        this.queryDescription = queryDescription;
    }

    /**
     * 只读浏览能力：不支持查询与详情。
     *
     * @param topNodeLabel   顶层节点称呼
     * @param childNodeLabel 子节点称呼
     * @return 能力对象
     */
    public static MiddlewareCapability browseOnly(String topNodeLabel, String childNodeLabel) {
        return new MiddlewareCapability(true, true, false, false, topNodeLabel, childNodeLabel, "", "");
    }

    /**
     * @return 是否支持列出顶层节点
     */
    public boolean topNodeSupported() {
        return topNodeSupported;
    }

    /**
     * @return 是否支持展开子节点
     */
    public boolean childNodeSupported() {
        return childNodeSupported;
    }

    /**
     * @return 是否支持只读查询
     */
    public boolean querySupported() {
        return querySupported;
    }

    /**
     * @return 是否支持查看单条详情
     */
    public boolean documentSupported() {
        return documentSupported;
    }

    /**
     * @return 顶层节点称呼
     */
    public String topNodeLabel() {
        return topNodeLabel;
    }

    /**
     * @return 子节点称呼
     */
    public String childNodeLabel() {
        return childNodeLabel;
    }

    /**
     * @return 查询占位提示
     */
    public String queryHint() {
        return queryHint;
    }

    /**
     * @return 查询语法说明
     */
    public String queryDescription() {
        return queryDescription;
    }

    @Override
    public String toString() {
        return "MiddlewareCapability{topNode=" + topNodeLabel + ", childNode=" + childNodeLabel
                + ", query=" + querySupported + ", document=" + documentSupported + '}';
    }

    // ==== Jackson getters (generated) ====
    // 本类用 fluent 访问器（xxx()）对外，Jackson 默认只认 getXxx()/isXxx()，
    // 因此这里补一层 getter 别名；既有调用方不受影响。

    /**
     * Jackson getter，对应 {@link #topNodeSupported()}。
     *
     * @return topNodeSupported
     */
    public boolean getTopNodeSupported() {
        return topNodeSupported();
    }

    /**
     * Jackson getter，对应 {@link #childNodeSupported()}。
     *
     * @return childNodeSupported
     */
    public boolean getChildNodeSupported() {
        return childNodeSupported();
    }

    /**
     * Jackson getter，对应 {@link #querySupported()}。
     *
     * @return querySupported
     */
    public boolean getQuerySupported() {
        return querySupported();
    }

    /**
     * Jackson getter，对应 {@link #documentSupported()}。
     *
     * @return documentSupported
     */
    public boolean getDocumentSupported() {
        return documentSupported();
    }

    /**
     * Jackson getter，对应 {@link #topNodeLabel()}。
     *
     * @return topNodeLabel
     */
    public String getTopNodeLabel() {
        return topNodeLabel();
    }

    /**
     * Jackson getter，对应 {@link #childNodeLabel()}。
     *
     * @return childNodeLabel
     */
    public String getChildNodeLabel() {
        return childNodeLabel();
    }

    /**
     * Jackson getter，对应 {@link #queryHint()}。
     *
     * @return queryHint
     */
    public String getQueryHint() {
        return queryHint();
    }

    /**
     * Jackson getter，对应 {@link #queryDescription()}。
     *
     * @return queryDescription
     */
    public String getQueryDescription() {
        return queryDescription();
    }
}
