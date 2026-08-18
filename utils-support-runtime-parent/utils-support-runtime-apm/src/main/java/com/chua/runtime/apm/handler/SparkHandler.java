package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spark Handler — intercepts Spark Dataset/DataFrame actions.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SparkHandler extends AbstractAppHandler {

    /**
     * DATASET
     */
    private static final String DATASET = "org/apache/spark/sql/Dataset";
    /**
     * action methods
     */
    private static final String[] ACTION_METHODS = {"collect", "show", "count", "head", "first", "take", "foreach", "write"};

    @Override
    public String name() {
        return "spark-handler";
    }

    @Override
    protected String enabledKey() {
        return "spark.enabled";
    }

    @Override
    protected Software software() {
        return Software.SPARK;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(DATASET, ACTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.SPARK)
                .host("spark")
                .port(0)
                .path("/")
                .build();
    }
}