package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * HBase 应用层 处理器 — 拦截 HBase Java 客户端 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code org.apache.hadoop.hbase.client.Table} — get / put / delete / scan / increment / append</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：HBase 不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HBaseHandler extends AbstractAppHandler {

    /**
     * Table 接口 / htable 实现类内部名
     */
    private static final String TABLE_CLASS = "org/apache/hadoop/hbase/client/Table";

    /**
     * htable 实现类内部名
     */
    private static final String HTABLE_CLASS = "org/apache/hadoop/hbase/client/HTable";

    /**
     * Table 方法集合
     */
    private static final String[] TABLE_METHODS = {"get", "put", "delete", "scan", "increment", "append"};

    @Override
    /** 名称 */
    public String name() {
        return "hbase-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "hbase.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.HBASE_CLIENT;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.HBASE;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(TABLE_CLASS, TABLE_METHODS);
        registerAll(HTABLE_CLASS, TABLE_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.HBASE)
                .software(Software.HBASE_CLIENT)
                .host("hbase")
                .port(Protocol.HBASE.defaultPort())
                .path("/")
                .build();
    }
}