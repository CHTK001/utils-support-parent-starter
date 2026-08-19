package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * MyBatis Handler — intercepts MyBatis SQL execution.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MyBatisHandler extends AbstractAppHandler {

    /**
     * sql 会话
     */
    private static final String SQL_SESSION = "org/apache/ibatis/session/SqlSession";
    /**
     * sql methods
     */
    private static final String[] SQL_METHODS = {"selectOne", "selectList", "selectMap", "insert", "update", "delete"};

    @Override
    /** Name */
    public String name() {
        return "mybatis-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "mybatis.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.MYBATIS;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    /** 注册Interceptors */
    protected void registerInterceptors() {
        registerAll(SQL_SESSION, SQL_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SQL)
                .software(Software.MYBATIS)
                .host("mybatis")
                .port(0)
                .path("/")
                .build();
    }
}