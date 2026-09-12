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
    private static final String[] CLIENT_METHODS = {"search", "get", "index", "delete", "update", "bulk", "count", "exists"};

    @Override
    /** 名称 */
    public String name() {
        return "opensearch-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "opensearch.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.OPENSEARCH;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.ELASTICSEARCH;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(REST_CLIENT, CLIENT_METHODS);
    }

    @Override
    /** 构建Target */
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