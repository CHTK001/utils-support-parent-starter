package com.chua.common.support.datasearch.exchange.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * 规范构造器：全量汇率表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。失败应答不含 {@code rates}，
     * 调用方以 {@code response.rates() != null} 判定成功，保留可空语义；币种汇率取自 JSON，
     * 允许存在空值（调用方按空值返回"该币种不可换算"），故用可空安全包装而非
     * {@code Map.copyOf}。</p>
     *
     * @param rates 全量汇率表
     */
    public ExchangeRateResponse {
        rates = rates == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(rates));
    }
}
