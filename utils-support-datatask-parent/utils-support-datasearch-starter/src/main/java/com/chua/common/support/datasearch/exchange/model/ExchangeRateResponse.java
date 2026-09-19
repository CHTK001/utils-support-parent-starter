package com.chua.common.support.datasearch.exchange.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * 汇率接口响应实体。
 *
 * <p>对应 open.er-api.com {@code /v6/latest/{base}} 响应结构，
 * {@code rates} 为币种 -> 汇率（1 单位 base 可兑换数量）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeRateResponse {

    /** 请求结果（成功 / 错误） */
    private String result;

    /** 提供商名称 */
    private String provider;

    /** 基准币种 */
    @JsonProperty("base_code")
    private String baseCode; // 基础编码

    /** 上次更新时间（Unix 秒） */
    @JsonProperty("time_last_update_unix")
    private long timeLastUpdateUnix; // 时间最后一个更新unix

    /** 全量汇率表 */
    private Map<String, Double> rates;
}
