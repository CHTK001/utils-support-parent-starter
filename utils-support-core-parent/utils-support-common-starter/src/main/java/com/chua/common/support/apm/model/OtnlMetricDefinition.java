package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlScope;

import java.util.List;

/**
 * 指标定义（供前端渲染指标下拉与卡片标题）。
 *
 * @param name        指标名，例如 {@code service_resp_time}
 * @param scope       作用域
 * @param label       展示名
 * @param unit        单位（ms / % / count / MB …）
 * @param description 说明
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlMetricDefinition(String name,
                                   OtnlScope scope,
                                   String label,
                                   String unit,
                                   String description) {

    /**
     * SkyWalking 服务级默认指标集（所有引擎都按这套名称返回，保证前端一份代码）
     */
    public static final List<OtnlMetricDefinition> SERVICE_DEFAULTS = List.of(
            new OtnlMetricDefinition("service_resp_time", OtnlScope.SERVICE, "响应时间", "ms", "服务平均响应耗时"),
            new OtnlMetricDefinition("service_sla", OtnlScope.SERVICE, "成功率", "%", "服务调用成功率"),
            new OtnlMetricDefinition("service_cpm", OtnlScope.SERVICE, "吞吐量", "cpm", "每分钟调用次数"),
            new OtnlMetricDefinition("service_p90", OtnlScope.SERVICE, "P90", "ms", "90 分位响应耗时"),
            new OtnlMetricDefinition("service_p95", OtnlScope.SERVICE, "P95", "ms", "95 分位响应耗时"),
            new OtnlMetricDefinition("service_p99", OtnlScope.SERVICE, "P99", "ms", "99 分位响应耗时")
    );

    /**
     * SkyWalking 端点级默认指标集
     */
    public static final List<OtnlMetricDefinition> ENDPOINT_DEFAULTS = List.of(
            new OtnlMetricDefinition("endpoint_resp_time", OtnlScope.ENDPOINT, "响应时间", "ms", "端点平均响应耗时"),
            new OtnlMetricDefinition("endpoint_sla", OtnlScope.ENDPOINT, "成功率", "%", "端点调用成功率"),
            new OtnlMetricDefinition("endpoint_cpm", OtnlScope.ENDPOINT, "吞吐量", "cpm", "每分钟调用次数"),
            new OtnlMetricDefinition("endpoint_p99", OtnlScope.ENDPOINT, "P99", "ms", "99 分位响应耗时")
    );

    /**
     * SkyWalking 实例级 JVM 指标集
     */
    public static final List<OtnlMetricDefinition> INSTANCE_DEFAULTS = List.of(
            new OtnlMetricDefinition("instance_jvm_cpu", OtnlScope.SERVICE_INSTANCE, "CPU", "%", "进程 CPU 使用率"),
            new OtnlMetricDefinition("instance_jvm_memory_heap", OtnlScope.SERVICE_INSTANCE, "堆内存", "MB", "JVM 堆使用量"),
            new OtnlMetricDefinition("instance_jvm_memory_noheap", OtnlScope.SERVICE_INSTANCE, "非堆内存", "MB", "JVM 非堆使用量"),
            new OtnlMetricDefinition("instance_jvm_thread_live", OtnlScope.SERVICE_INSTANCE, "活线程", "count", "JVM 活动线程数"),
            new OtnlMetricDefinition("instance_jvm_gc_time", OtnlScope.SERVICE_INSTANCE, "GC 耗时", "ms", "每分钟 GC 耗时"),
            new OtnlMetricDefinition("instance_clr_cpu", OtnlScope.SERVICE_INSTANCE, "CLR CPU", "%", ".NET 进程 CPU 使用率")
    );

    /**
     * 全局默认指标集
     */
    public static final List<OtnlMetricDefinition> ALL_DEFAULTS = List.of(
            new OtnlMetricDefinition("all_p99", OtnlScope.ALL, "全局 P99", "ms", "全局 99 分位响应耗时"),
            new OtnlMetricDefinition("all_heatmap", OtnlScope.ALL, "全局热力图", "%", "全局响应时间分布")
    );
}
