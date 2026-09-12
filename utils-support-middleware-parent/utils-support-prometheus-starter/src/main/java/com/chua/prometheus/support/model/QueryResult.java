package com.chua.prometheus.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
* Prometheus 即时查询结果(向量)
* <p>
* 对应 {@code /api/v1/query} 返回的 结果类型=向量 结构。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    /**
    * 结果类型: 向量 / matrix / scalar / 字符串
     */
    private String resultType;

    /**
    * 结果列表
     */
    @Builder.Default
    /** 结果 */
    private List<PrometheusMetric> result = new ArrayList<>();

    /**
    * 是否存在数据
    *
    * @return 是否
     */
    public boolean hasData() {
        return result != null && !result.isEmpty();
    }
}