package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * 打开搜索 处理器 — intercepts 打开搜索 客户端 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OpenSearchHandler extends AbstractAppHandler {

    /**
     * rest 客户端
     */
    private static final String REST_CLIENT = "org/opensearch/client/RestHighLevelClient";
    /**
     * 客户端 方法
     */
    private static final String[] CLIENT_METHODS =
            {"search", "get", "index", "delete", "update", "bulk", "count", "exists"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "opensearch-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "opensearch.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.OPENSEARCH;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.ELASTICSEARCH;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(REST_CLIENT, CLIENT_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.ELASTICSEARCH)
                .software(Software.OPENSEARCH)
                .host("opensearch")
                .port(Protocol.ELASTICSEARCH.defaultPort())
                .path("/")
                .build();
    }
}