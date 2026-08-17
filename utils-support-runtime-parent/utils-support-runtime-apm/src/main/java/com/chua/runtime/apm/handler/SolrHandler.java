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

    private static final String SOLR_CLIENT = "org/apache/solr/client/solrj/SolrClient";
    private static final String[] SOLR_METHODS = {"request"};

    @Override
    public String name() {
        return "solr-handler";
    }

    @Override
    protected String enabledKey() {
        return "solr.enabled";
    }

    @Override
    protected Software software() {
        return Software.SOLR;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.SOLR;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(SOLR_CLIENT, SOLR_METHODS);
    }

    @Override
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