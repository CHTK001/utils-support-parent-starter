package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * MyBatis 处理器 — intercepts MyBatis SQL 执行.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MyBatisHandler extends AbstractAppHandler {

    /**
     * SQL 会话
     */
    private static final String SQL_SESSION = "org/apache/ibatis/session/SqlSession";
    /**
     * SQL 方法
     */
    private static final String[] SQL_METHODS = {"selectOne", "selectList", "selectMap", "insert", "update", "delete"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "mybatis-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "mybatis.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.MYBATIS;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(SQL_SESSION, SQL_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
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