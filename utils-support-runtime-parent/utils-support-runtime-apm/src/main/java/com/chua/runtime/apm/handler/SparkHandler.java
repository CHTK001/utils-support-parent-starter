package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spark 处理器 — intercepts Spark 数据集/数据帧 Actions.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SparkHandler extends AbstractAppHandler {

    /**
     * 数据集
     */
    private static final String DATASET = "org/apache/spark/sql/Dataset";
    /**
     * 动作 方法
     */
    private static final String[] ACTION_METHODS = {"collect", "show", "count", "head", "first", "take", "foreach", "write"};

    @Override
    /**
     * 名称
    */
    public String name() {
        return "spark-handler";
    }

    @Override
    /**
     * 已启用键
    */
    protected String enabledKey() {
        return "spark.enabled";
    }

    @Override
    /**
     * Software
    */
    protected Software software() {
        return Software.SPARK;
    }

    @Override
    /**
     * 协议
    */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /**
     * 注册拦截器
    */
    protected void registerInterceptors() {
        registerAll(DATASET, ACTION_METHODS);
    }

    @Override
    /**
     * 构建Target
    */
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