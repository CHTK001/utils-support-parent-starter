package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
   * Elasticsearch 应用层 处理器 — 拦截 Elasticsearch 客户端关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.elasticsearch.client.RestHighLevelClient} — search / index / get / delete / update / bulk 等</li>
 *   <li>{@code org.elasticsearch.client.RestClient} — performRequest（底层请求入口）</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Elasticsearch 客户端不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ElasticsearchHandler extends AbstractAppHandler {

    /**
      * resthigh级别客户端 类内部名
     */
    private static final String HIGH_LEVEL_CLIENT = "org/elasticsearch/client/RestHighLevelClient";

    /**
      * rest客户端 类内部名
     */
    private static final String REST_CLIENT = "org/elasticsearch/client/RestClient";

    /**
     * 高层客户端方法集合
     */
    private static final String[] HIGH_LEVEL_METHODS = {
            "search", "get", "index", "delete", "update", "bulk", "count",
            "exists", "info", "ping", "msearch", "scroll", "clearScroll"
    };

    /**
     * 底层 HTTP 请求方法集合
     */
    private static final String[] REST_METHODS = {"performRequest"};

    @Override
    /** 名称 */
    public String name() {
        return "elasticsearch-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "elasticsearch.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.ELASTICSEARCH;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.ELASTICSEARCH;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(HIGH_LEVEL_CLIENT, HIGH_LEVEL_METHODS);
        registerAll(REST_CLIENT, REST_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object host = findField(instance, "hosts");
        String url = host != null ? String.valueOf(host) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.ELASTICSEARCH)
                .software(Software.ELASTICSEARCH)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "elasticsearch")
                .port(parseUrlPort(url, Protocol.ELASTICSEARCH.defaultPort()))
                .path("/")
                .build();
    }
}