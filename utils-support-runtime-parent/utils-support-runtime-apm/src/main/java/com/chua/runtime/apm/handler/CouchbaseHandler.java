package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Couchbase 处理器 — intercepts Couchbase bucket operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CouchbaseHandler extends AbstractAppHandler {

    /**
     * BUCKET
     */
    private static final String BUCKET = "com/couchbase/client/java/Bucket";
    /**
     * 集合
     */
    private static final String COLLECTION = "com/couchbase/client/java/Collection";
    /**
     * bucket 方法
     */
    private static final String[] BUCKET_METHODS = {"defaultCollection", "collection"};
    /**
     * 集合 方法
     */
    private static final String[] COLLECTION_METHODS = {"get", "insert", "upsert", "replace", "remove", "query"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "couchbase-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "couchbase.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.COUCHBASE;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.COUCHBASE;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(BUCKET, BUCKET_METHODS);
        registerAll(COLLECTION, COLLECTION_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.COUCHBASE)
                .software(Software.COUCHBASE)
                .host("couchbase")
                .port(Protocol.COUCHBASE.defaultPort())
                .path("/")
                .build();
    }
}