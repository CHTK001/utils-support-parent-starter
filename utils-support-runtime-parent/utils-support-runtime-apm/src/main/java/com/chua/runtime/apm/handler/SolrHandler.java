package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Solr 应用层 Handler — 拦截 Apache Solr Client 请求并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.solr.client.solrj.SolrClient} — request</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：Solr 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SolrHandler extends AbstractAppHandler {

    /**
     * solr 客户端
     */
    private static final String SOLR_CLIENT = "org/apache/solr/client/solrj/SolrClient";
    /**
     * solr methods
     */
    private static final String[] SOLR_METHODS = {"request"};

    @Override
    /** Name */
    public String name() {
        return "solr-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "solr.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SOLR;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.SOLR;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(SOLR_CLIENT, SOLR_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SOLR)
                .software(Software.SOLR)
                .host("solr")
                .port(Protocol.SOLR.defaultPort())
                .path("/")
                .build();
    }
}