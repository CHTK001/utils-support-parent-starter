package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
* Spring 数据 JPA 处理器 — intercepts Spring 数据 JPA 仓库 operations.
*
* @author CH
* @since 4.0.0.42
 */
public class SpringDataJpaHandler extends AbstractAppHandler {

    /**
    * JPA 仓库
     */
    private static final String JPA_REPOSITORY = "org/springframework/data/repository/CrudRepository";
    /**
    * 仓库 方法
     */
    private static final String[] REPOSITORY_METHODS = {"save", "findById", "findAll", "deleteById", "count"};

    @Override
    /** 名称 */
    public String name() {
        return "spring-data-jpa-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "spring-data-jpa.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SPRING_DATA_JPA;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(JPA_REPOSITORY, REPOSITORY_METHODS);
    }

    @Override
    /** 构建Target */
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.SQL)
                .software(Software.SPRING_DATA_JPA)
                .host("spring-data-jpa")
                .port(0)
                .path("/")
                .build();
    }
}