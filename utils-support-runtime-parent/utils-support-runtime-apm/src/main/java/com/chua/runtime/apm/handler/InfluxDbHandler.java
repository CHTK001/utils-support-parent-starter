package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * influxdb 应用层 处理器 — 拦截 influxdb Java 客户端 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.influxdb.InfluxDB} — write / query / queryAsync / ping（核心读写入口）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：InfluxDB 客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InfluxDbHandler extends AbstractAppHandler {

    /**
     * influxdb 接口类内部名
     */
    private static final String INFLUX_CLIENT = "org/influxdb/InfluxDB";

    /**
     * influxdb 方法集合（读写/探测）
     */
    private static final String[] CLIENT_METHODS = {"write", "query", "queryAsync", "ping", "batch"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "influxdb-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "influxdb.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.INFLUXDB_CLIENT;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.INFLUXDB;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(INFLUX_CLIENT, CLIENT_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object url = instance != null ? findField(instance, "url") : null;
        String urlStr = url != null ? String.valueOf(url) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INFLUXDB)
                .software(Software.INFLUXDB_CLIENT)
                .host(parseUrlHost(urlStr) != null ? parseUrlHost(urlStr) : "influxdb")
                .port(parseUrlPort(urlStr, Protocol.INFLUXDB.defaultPort()))
                .path("/")
                .build();
    }
}