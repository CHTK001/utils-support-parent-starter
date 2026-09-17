package com.chua.common.support.network.rpc;

import lombok.Data;

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
@Data
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
    * 创建永不变化的空指标快照（供默认实现使用）。
    *
    * @param protocol 协议名称
    * @return 空指标快照
    */
    public static RpcMetrics immutable(String protocol) {
        RpcMetrics metrics = new RpcMetrics(protocol);
        metrics.setConnections(Collections.emptyList());
        metrics.setMethodStats(Collections.emptyList());
        return metrics;
    }

    /**
    * 方法级调用统计。
    *
    * @param method         方法全限定名（接口全名 + 方法名）
    * @param total          调用总数
    * @param success        成功次数
    * @param failure        失败次数
    * @param avgDurationMs  平均耗时（毫秒）
    * @param maxDurationMs  最大耗时（毫秒）
    * @param minDurationMs  最小耗时（毫秒）
    * @param lastDurationMs 最近一次耗时（毫秒）
    * @param lastCallTime   最近一次调用时间戳（毫秒），无记录为 0
    * @param lastResult     最近一次结果（SUCCESS / FAILURE / NONE）
    * @param lastError      最近一次失败时的错误信息，成功或未发生失败时为空字符串
    */
    public record MethodStat(String method, long total, long success, long failure,
                             long avgDurationMs, long maxDurationMs, long minDurationMs,
                             long lastDurationMs, long lastCallTime, String lastResult, String lastError) {
    }
}
