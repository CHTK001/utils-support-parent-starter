package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.handler.ServerHandlerFactory;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
* Shell 命令匹配的 ServerFilter。
* <p>
* 类似 {@link UrlMappingServerFilter}，将 Shell 命令名称映射到处理器。
* 专为 SSH Shell 服务端设计，支持通过 {@link com.chua.ssh.support.annotations.ShellMethod} 注解声明式注册命令。
* </p>
*
* <pre>{@code
* ShellUrlServerFilter filter = new ShellUrlServerFilter(objectContext);
* filter.route("ls", (req, res) -> res.setBody("file1.txt  file2.txt"));
* filter.route("hello", (req, res) -> res.setBody("Hello, " + req.getParam("0") + "!"));
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see UrlMappingServerFilter
 */
@Spi("shell-url-mapping")
@SpiDescribe("Shell 命令到处理器映射过滤器")
public class ShellUrlServerFilter implements EndServerFilter {

    /**
    * 处理器工厂，管理路由注册与匹配
     */
    private final ServerHandlerFactory<ServerHandlerAnnotationParser> factory;

    /**
    * 构造 Shell 命令过滤器。
    *
    * @param objectContext 对象上下文
     */
    public ShellUrlServerFilter(ObjectContext objectContext) {
        this.factory = new ServerHandlerFactory<>(objectContext);
        this.factory.initialize(ServerHandlerAnnotationParser.class, this);
    }

    /**
    * 注册命令处理器。
    *
    * @param commandName 命令名称
    * @param handler     命令处理器
    * @return this
     */
    public ShellUrlServerFilter route(String commandName, ServerHandler handler) {
        if (commandName == null || commandName.isBlank()) {
            throw new IllegalArgumentException("命令名称不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("命令处理器不能为空");
        }
        String normalized = normalizePath(commandName);
        factory.route(normalized, handler);
        return this;
    }

    /**
    * 批量注册命令处理器。
    *
    * @param routes 命令名称到处理器的映射
    * @return this
     */
    public ShellUrlServerFilter routes(Map<String, ServerHandler> routes) {
        if (routes != null) {
            routes.forEach(this::route);
        }
        return this;
    }

    /**
    * 移除命令处理器。
    *
    * @param commandName 命令名称
    * @return this
     */
    public ShellUrlServerFilter removeRoute(String commandName) {
        if (commandName != null) {
            factory.removeRoute(normalizePath(commandName));
        }
        return this;
    }

    /**
    * 获取所有已注册的命令名称列表。
    *
    * @return 命令名称集合
     */
    public Set<String> getCommandNames() {
        return new LinkedHashSet<>(factory.getAnyMethodRoutes().keySet());
    }

    /**
    * 获取已注册的命令数量。
    *
    * @return 命令数量
     */
    public int routeCount() {
        return factory.routeCount();
    }

    /**
    * 获取指定命令的处理器。
    *
    * @param commandName 命令名称
    * @return 处理器，未注册返回 null
     */
    public ServerHandler getHandler(String commandName) {
        if (commandName == null) {
            return null;
        }
        return factory.resolveHandler(normalizePath(commandName));
    }

    /**
    * 获取处理器工厂。
    *
    * @return 处理器工厂
     */
    public ServerHandlerFactory<ServerHandlerAnnotationParser> getFactory() {
        return factory;
    }

    @Override
    /** Do过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null || path.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ServerHandler handler = factory.resolveHandler(normalizePath(path));
        if (handler != null) {
            handler.handle(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    /** 获取过滤Id */
    public String getFilterId() {
        return "ShellUrlServerFilter";
    }

    @Override
    /** SupportPath */
    public String supportPath() {
        return null;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.SSH};
    }

    /** NormalizePath */
    private static String normalizePath(String commandName) {
        if (commandName == null || commandName.isBlank()) {
            return "/";
        }
        return commandName.startsWith("/") ? commandName : "/" + commandName;
    }
}
