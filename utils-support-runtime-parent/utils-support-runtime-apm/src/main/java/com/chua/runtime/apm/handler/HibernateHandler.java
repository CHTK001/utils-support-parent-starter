package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
* Hibernate 处理器 — intercepts Hibernate ORM 会话 operations.
*
* @author CH
* @since 4.0.0.42
 */
public class HibernateHandler extends AbstractAppHandler {

    /**
    * 会话
     */
    private static final String SESSION = "org/hibernate/Session";
    /**
    * 会话 方法
     */
    private static final String[] SESSION_METHODS = {"save", "update", "delete", "load", "get", "merge", "persist"};

    @Override
    /** 名称 */
    public String name() {
        return "hibernate-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "hibernate.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.HIBERNATE;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(SESSION, SESSION_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SQL)
                .software(Software.HIBERNATE)
                .host("hibernate")
                .port(0)
                .path("/")
                .build();
    }
}