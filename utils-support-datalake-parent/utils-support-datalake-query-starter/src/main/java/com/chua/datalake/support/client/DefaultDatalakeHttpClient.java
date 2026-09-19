package com.chua.datalake.support.client;

import java.util.List;
import java.util.Map;

/**
 * 默认 HTTP客户端 实现，基于 JDK HTTP客户端 调用 api服务端 /查询 路由。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultDatalakeHttpClient extends DatalakeHttpClient {

    /**
     * 创建 默认数据湖http客户端 实例
     * @param baseUrl baseurl
     */
    public DefaultDatalakeHttpClient(String baseUrl) {
        super(baseUrl);
    }

    /**
     * 解析响应为对象列表。
     *
     * @param sql  SQL 字符串
     * @param args 参数列表
     * @return 结果列表（默认空列表）
     */
    public List<Map<String, Object>> queryAsList(String sql, Object... args) {
        return java.util.Collections.emptyList();
    }
}
