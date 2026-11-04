package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlScope;

import java.util.List;

/**
 * 指标时序。
 *
 * @param name  指标名
 * @param scope 作用域
 * @param label 实体展示名（服务名 / 实例名 / 端点名）
 * @param unit  单位
 * @param points 数据点
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlMetricSeries(String name,
                               OtnlScope scope,
                               String label,
                               String unit,
                               List<OtnlMetricPoint> points) {

    /**
     * 空时序
     *
     * @param name  指标名
     * @param scope 作用域
     * @param label 实体名
     * @return 时序
     */
    public static OtnlMetricSeries empty(String name, OtnlScope scope, String label) {
        return new OtnlMetricSeries(name, scope, label, null, List.of());
    }

    /**
     * 单个数据点
     *
     * @param time  毫秒时间戳
     * @param value 值
     * @author CH
     * @since 4.0.0.51
     */
    public record OtnlMetricPoint(long time, double value) {
    }
}
