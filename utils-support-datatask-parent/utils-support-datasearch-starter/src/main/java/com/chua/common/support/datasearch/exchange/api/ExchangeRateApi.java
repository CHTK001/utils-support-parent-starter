package com.chua.common.support.datasearch.exchange.api;

import com.chua.common.support.datasearch.exchange.model.ExchangeRateResponse;
import com.chua.common.support.network.annotations.RequestMethod;

/**
 * 汇率公开 API 声明式接口（实体查询）。
 *
 * <p>通过 {@code HttpInvoker}/{@code HttpApiFactory} 动态代理调用，
 * 数据源为 open.er-api.com 免费接口（无需 key，约 166 个币种）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RequestMethod("https://open.er-api.com")
public interface ExchangeRateApi {

    /**
     * 查询以 USD 为基准的全量汇率。
     *
     * @return 汇率响应实体（含 rates 表）
     */
    @RequestMethod(value = "/v6/latest/USD", method = "GET")
    ExchangeRateResponse latestUsd();
}
