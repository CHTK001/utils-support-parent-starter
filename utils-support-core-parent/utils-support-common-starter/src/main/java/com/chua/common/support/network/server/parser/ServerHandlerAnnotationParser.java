package com.chua.common.support.network.server.parser;

import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 服务器处理器注解解析器 SPI。
 *
 * <p>各框架（Spring MVC、Quarkus、自定义注解）提供自己的实现，
 * 从 {@link ObjectContext} 中查找自己框架的注解 Bean，并解析为 {@link ServerHandler}。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi
public interface ServerHandlerAnnotationParser {

    /**
     * 解析器优先级，数值越高优先级越高。
     *
     * @return 优先级
     */
    default int getPriority() {
        return 0;
    }

    /**
     * 从 {@link ObjectContext} 中查找该解析器支持的注解 Bean，并生成处理器列表。
     *
     * @param objectContext 对象上下文
     * @param serverFilter
     * @return 处理器列表，每个处理器对应一个注解映射的路由
     */
    List<ServerHandler> parse(ObjectContext objectContext, ServerFilter serverFilter);
}