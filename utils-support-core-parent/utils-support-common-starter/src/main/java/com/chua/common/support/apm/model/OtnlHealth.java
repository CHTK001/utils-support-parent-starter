package com.chua.common.support.apm.model;

/**
 * 健康度（SkyWalking UI 首页的饱和度派生指标）。
 *
 * @param health       HEALTHY / DEGRADED / SATURATED
 * @param qps          每秒请求数
 * @param errorRate    错误率百分比
 * @param avgMs        平均耗时毫秒
 * @param p99Ms        P99 毫秒
 * @param services     服务数
 * @param endpoints    端点数
 * @param alarms       窗口内告警数
 * @param message      说明（无数据时回显原因）
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlHealth(String health,
                         double qps,
                         double errorRate,
                         double avgMs,
                         double p99Ms,
                         int services,
                         int endpoints,
                         int alarms,
                         String message) {

    /**
     * 无数据健康度
     *
     * @param message 说明
     * @return 健康度
     */
    public static OtnlHealth empty(String message) {
        return new OtnlHealth("UNKNOWN", 0, 0, 0, 0, 0, 0, 0, message);
    }
}
