package com.chua.common.support.datasearch.exchange.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 汇率接口响应实体。
 * <p>对应 open.er-api.com {@code /v6/latest/{base}} 响应结构，
 * {@code rates} 为币种 -> 汇率（1 单位 base 可兑换数量）。</p>
 *
 * @param result 请求结果（成功 / 错误）
 * @param provider 提供商名称
 * @param baseCode 基准币种
 * @param timeLastUpdateUnix 上次更新时间（Unix 秒）
 * @param rates 全量汇率表
 *
 * @author CH
 * @since 4.0.0.42
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeRateResponse(
        String result,
        String provider,
        @JsonProperty("base_code") String baseCode,
        @JsonProperty("time_last_update_unix") long timeLastUpdateUnix,
        Map<String, Double> rates
) {
}
