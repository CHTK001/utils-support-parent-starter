package com.chua.starter.datasync.source;

import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.ReactiveHttpClient;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Agent 数据同步 源，从远程 Agent 拉取数据。
 *
 * <p>通过 HTTP GET 请求调用 Agent 接口获取数据，支持 JSON 响应解析。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HttpAgentDataSyncSource implements DataSyncAgentSource {

    /** 数据源标识 */
    private final String sourceId;
    /** 输入标识 */
    private final String inputId;
    /** 代理标识 */
    private final String agentId;
    /** 代理服务地址 */
    private final String agentUrl;
    /** HTTP 客户端（懒加载） */
    private volatile ReactiveHttpClient httpClient;

    /**
    * 创建 httpAgent数据同步源 实例
    *
    * @param sourceId 源标识
    * @param inputId  输入标识
    * @param agentId  Agent标识
    * @param agentUrl Agenturl
    */
    public HttpAgentDataSyncSource(String sourceId, String inputId, String agentId, String agentUrl) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.agentId = agentId;
        this.agentUrl = agentUrl;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String inputId() {
        return inputId;
    }

    @Override
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        ReactiveHttpClient client = getHttpClient();
        ClientRequest request = ClientRequest.of(agentUrl);
        java.util.Map<String, String> stringParams = new java.util.LinkedHashMap<>();
        params.forEach((k, v) -> stringParams.put(k, v == null ? "" : String.valueOf(v)));
        request.setParams(stringParams);
        try {
            ClientResponse response = client.execute(request);
            if (!response.isSuccess() || response.getBody() == null) {
                return Flux.empty();
            }
            return Flux.fromIterable(parseJsonResponse(response.getBodyString()));
        } catch (Exception e) {
            throw new RuntimeException("调用 Agent 失败: " + agentUrl, e);
        }
    }

    /**
     * 解析 JSON 响应，支持数组或单对象。
     * @return 获取http客户端的结果
     * @param body 主体
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonResponse(String body) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        body = body.trim();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object obj = mapper.readValue(body, Object.class);
            if (obj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        map.forEach((k, v) -> row.put(String.valueOf(k), v));
                        rows.add(row);
                    }
                }
            } else if (obj instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((k, v) -> row.put(String.valueOf(k), v));
                rows.add(row);
            }
        } catch (Exception e) {
            rows.add(Map.of("raw", body));
        }
        return rows;
    }

    /**
     * 获取Http客户端。
     *
     * @return ReactiveHttp客户端 对象
     */
    private ReactiveHttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = ReactiveHttpClient.of();
                }
            }
        }
        return httpClient;
    }

    @Override
    public void close() {
        ReactiveHttpClient client = httpClient;
        if (client != null) {
            client.close();
            httpClient = null;
        }
    }
}
