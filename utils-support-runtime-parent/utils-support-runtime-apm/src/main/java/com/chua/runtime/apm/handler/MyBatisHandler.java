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

    @Override
    /** 名称 */
    public String name() {
        return "mybatis-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "mybatis.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.MYBATIS;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    /** 注册拦截器 */
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