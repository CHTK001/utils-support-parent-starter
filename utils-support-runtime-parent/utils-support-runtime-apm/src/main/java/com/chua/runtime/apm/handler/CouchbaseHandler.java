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

    @Override
    /**
     * 名称
    */
    public String name() {
        return "couchbase-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "couchbase.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.COUCHBASE;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.COUCHBASE;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(BUCKET, BUCKET_METHODS);
        registerAll(COLLECTION, COLLECTION_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
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