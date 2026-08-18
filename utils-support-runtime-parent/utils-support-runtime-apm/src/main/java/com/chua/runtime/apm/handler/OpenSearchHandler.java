package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * OpenSearch Handler — intercepts OpenSearch client operations.
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
     * 客户端 methods
     */
    private static final String[] CLIENT_METHODS = {"search", "get", "index", "delete", "update", "bulk", "count", "exists"};

    @Override
    public String name() {
        return "opensearch-handler";
    }

    @Override
    protected String enabledKey() {
        return "opensearch.enabled";
    }

    @Override
    protected Software software() {
        return Software.OPENSEARCH;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.ELASTICSEARCH;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(REST_CLIENT, CLIENT_METHODS);
    }

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