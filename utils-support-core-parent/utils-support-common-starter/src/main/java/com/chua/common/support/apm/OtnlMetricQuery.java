// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.apm;

/**
 * 指标查询条件。
 * <p>
 * 与 SkyWalking 的 {@code readMetricsValues(condition: MetricsCondition)} 对齐：
 * 名称 + 作用域 + 实体（服务名 / 实例名 / 端点名）三者一起决定一条曲线。
 * </p>
 *
 * @param name          指标名，例如 {@code service_resp_time}
 * @param scope         作用域
 * @param serviceName   服务名（SERVICE 及以上作用域必填）
 * @param instanceName  实例名（SERVICE_INSTANCE 作用域必填）
 * @param endpointName  端点名（ENDPOINT 作用域必填）
 * @param startTime     起始毫秒时间戳
 * @param endTime       结束毫秒时间戳
 * @param step          聚合步长：SECOND / MINUTE / HOUR / DAY
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlMetricQuery(String name,
                              OtnlScope scope,
                              String serviceName,
                              String instanceName,
                              String endpointName,
                              long startTime,
                              long endTime,
                              String step) {

    /**
     * 最近 N 分钟的服务级指标查询
     *
     * @param name        指标名
     * @param serviceName 服务名
     * @param minutes     分钟数
     * @return 查询条件
     */
    public static OtnlMetricQuery service(String name, String serviceName, int minutes) {
        long end = System.currentTimeMillis();
        return new OtnlMetricQuery(name, OtnlScope.SERVICE, serviceName, null, null,
                end - Math.max(1, minutes) * 60_000L, end, "MINUTE");
    }

    /**
     * 归一化作用域（null → SERVICE）
     *
     * @return 作用域
     */
    public OtnlScope scopeOrService() {
        return scope == null ? OtnlScope.SERVICE : scope;
    }

    /**
     * 归一化步长（null → MINUTE）
     *
     * @return 步长
     */
    public String stepOrMinute() {
        return (step == null || step.isBlank()) ? "MINUTE" : step;
    }

    /**
     * 归一化结束时间（<=0 时取当前时间）
     *
     * @return 结束时间
     */
    public long endTimeOrNow() {
        return endTime <= 0 ? System.currentTimeMillis() : endTime;
    }

    /**
     * 归一化起始时间（<=0 时取结束前 30 分钟）
     *
     * @return 起始时间
     */
    public long startTimeOrWindow() {
        long end = endTimeOrNow();
        return startTime <= 0 ? end - 30 * 60_000L : startTime;
    }
}