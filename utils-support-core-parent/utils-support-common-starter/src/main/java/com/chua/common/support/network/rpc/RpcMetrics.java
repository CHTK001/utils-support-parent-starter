package com.chua.common.support.network.rpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RPC 运行时指标快照，由各协议实现按能力填充。
 *
 * <p>作为监控 endpoint 的输出模型，字段全部可空/可为零，
 * 未提供某指标的实现保持默认值即可。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RpcMetrics {

    /**
     * 协议名称
     */
    private String protocol = "unknown";
    /**
     * 服务启动时间戳（毫秒）
     */
    private long startTime = System.currentTimeMillis();
    /**
     * 累计调用总数
     */
    private long totalCalls;
    /**
     * 累计成功次数
     */
    private long successCalls;
    /**
     * 累计失败次数
     */
    private long failureCalls;
    /**
     * 当前在途调用数
     */
    private long activeCalls;
    /**
     * 当前连接总数
     */
    private long totalConnections;
    /**
     * 已暴露服务数
     */
    private int serviceCount;
    /**
     * 连接列表
     */
    private List<RpcConnectionInfo> connections = new ArrayList<>();
    /**
     * 方法级统计（可选）
     */
    private List<MethodStat> methodStats = new ArrayList<>();

    /**
     * 创建指定协议的指标快照。
     *
     * @param protocol 协议名称
     */
    public RpcMetrics(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 创建空指标快照。
     *
     * @param protocol 协议名称
     * @return 空指标快照
     */
    public static RpcMetrics empty(String protocol) {
        return new RpcMetrics(protocol);
    }

    /**
     * 创建永不变化的空指标快照（供默认实现使用）。
     *
     * @param protocol 协议名称
     * @return 空指标快照
     */
    public static RpcMetrics immutable(String protocol) {
        RpcMetrics metrics = new RpcMetrics(protocol);
        metrics.connections = Collections.emptyList();
        metrics.methodStats = Collections.emptyList();
        return metrics;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getTotalCalls() {
        return totalCalls;
    }

    public void setTotalCalls(long totalCalls) {
        this.totalCalls = totalCalls;
    }

    public long getSuccessCalls() {
        return successCalls;
    }

    public void setSuccessCalls(long successCalls) {
        this.successCalls = successCalls;
    }

    public long getFailureCalls() {
        return failureCalls;
    }

    public void setFailureCalls(long failureCalls) {
        this.failureCalls = failureCalls;
    }

    public long getActiveCalls() {
        return activeCalls;
    }

    public void setActiveCalls(long activeCalls) {
        this.activeCalls = activeCalls;
    }

    public long getTotalConnections() {
        return totalConnections;
    }

    public void setTotalConnections(long totalConnections) {
        this.totalConnections = totalConnections;
    }

    public int getServiceCount() {
        return serviceCount;
    }

    public void setServiceCount(int serviceCount) {
        this.serviceCount = serviceCount;
    }

    public List<RpcConnectionInfo> getConnections() {
        return connections;
    }

    public void setConnections(List<RpcConnectionInfo> connections) {
        this.connections = connections;
    }

    public List<MethodStat> getMethodStats() {
        return methodStats;
    }

    public void setMethodStats(List<MethodStat> methodStats) {
        this.methodStats = methodStats;
    }

    /**
     * 方法级调用统计。
     *
     * @param method         方法全限定名（接口全名 + 方法名）
     * @param total          调用总数
     * @param success        成功次数
     * @param failure        失败次数
     * @param avgDurationMs  平均耗时（毫秒）
     * @param lastDurationMs 最近一次耗时（毫秒）
     */
    public record MethodStat(String method, long total, long success, long failure,
                             long avgDurationMs, long lastDurationMs) {
    }
}