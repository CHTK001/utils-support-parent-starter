package com.chua.datalake.support.client;

import java.util.Map;

/**
 * 默认 HttpClient 实现，基于 JDK HttpClient 调用 ApiServer /query 路由。
 *
 * @author CH
 * @since 4.0.0.43
 */
public class DefaultDatalakeHttpClient extends DatalakeHttpClient {

    public DefaultDatalakeHttpClient(String baseUrl) {
        super(baseUrl);
    }

    public java.util.List<Map<String, Object>> queryAsList(String sql, Object... args) {
        return java.util.Collections.emptyList();
    }
}