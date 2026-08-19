package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * Spring Data JPA Handler — intercepts Spring Data JPA repository operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SpringDataJpaHandler extends AbstractAppHandler {

    /**
     * jpa repository
     */
    private static final String JPA_REPOSITORY = "org/springframework/data/repository/CrudRepository";
    /**
     * repository methods
     */
    private static final String[] REPOSITORY_METHODS = {"save", "findById", "findAll", "deleteById", "count"};

    @Override
    /** Name */
    public String name() {
        return "spring-data-jpa-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "spring-data-jpa.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.SPRING_DATA_JPA;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.SQL;
    }

    @Override
    /** 注册Interceptors */
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