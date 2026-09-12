package com.chua.runtime.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 依赖图节点 — 一次传输的边（源 → Target）。
*
* <p>聚合统计：相同源和目标的多次传输累计 callCount、totalDuration，
* 平均耗时 = total持续时间 / call数量。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DependencyEdge {

    /**
    * 源端点
     */
    private Endpoint source;

    /**
    * 目标端点
     */
    private Endpoint target;

    /**
    * 协议
     */
    private Protocol protocol;

    /**
    * 软件栈
     */
    private Software software;

    /**
    * 调用次数
     */
    @Builder.Default
    /** Call数量 */
    private long callCount = 0;

    /**
    * 总耗时（毫秒）
     */
    @Builder.Default
    /** 总数持续时间 */
    private long totalDuration = 0;

    /**
    * 错误次数
     */
    @Builder.Default
    /** 错误数量 */
    private long errorCount = 0;

    /**
    * 最近一次错误信息
     */
    private String lastError;

    /**
    * 最近一次调用时间戳（毫秒）
     */
    @Builder.Default
    /** 最后call时间 */
    private long lastCallTime = 0;

    /**
    * 平均耗时（毫秒）
    *
    * @return 平均耗时
     */
    public double avgDuration() {
        if (callCount == 0) {
            return 0;
        }
        return (double) totalDuration / (double) callCount;
    }

    /**
    * 累计一次传输。
    *
    * @param duration 本次耗时（毫秒）
    * @param isError  是否错误
    * @param error    错误信息（可空）
    * @return this
     */
    public DependencyEdge record(long duration, boolean isError, String error) {
        this.callCount++;
        this.totalDuration += duration;
        this.lastCallTime = System.currentTimeMillis();
        if (isError) {
            this.errorCount++;
            this.lastError = error;
        }
        return this;
    }

    /**
    * 边的稳定 标识。
    *
    * @return source 节点标识 → Target 节点标识
     */
    public String edgeId() {
        return source.nodeId() + " -> " + target.nodeId();
    }
}