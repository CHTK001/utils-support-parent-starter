package com.chua.common.support.network.server;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.objects.ObjectContext;
import com.chua.common.support.spi.ServiceProvider;

/**
 * Server 链式构建器，内部通过 SPI 创建 {@link Server} 实例。
 *
 * <p>使用方式：
 * <pre>{@code
 * Server server = ServerBuilder.create()
 *     .type("jdk")
 *     .port(8080)
 *     .host("127.0.0.1")
 *     .mapping("/hello", HttpMethod.GET, (req, resp) -> resp.setResult("Hello"))
 *     .build();
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ServerBuilder {

    /** 设置 */
    private ServerSetting setting;
    /**
     * 类型
     */
    private String type = "jdk";
    /** Object上下文 */
    private ObjectContext objectContext;
    /** 服务器 */
    private Server server;

    /** 创建 ServerBuilder 实例 */
    private ServerBuilder() {
    }

    /**
     * 创建构建器实例。
     *
     * @return ServerBuilder
     */
    public static ServerBuilder create() {
        return new ServerBuilder();
    }

    /**
     * 设置 SPI 类型标识。
     *
     * @param type 类型，如 {@code "jdk"}、{@code "netty"}、{@code "vertx"} 等
     * @return this
     */
    public ServerBuilder type(String type) {
        this.type = type;
        return this;
    }

    /**
     * 设置监听端口。
     *
     * @param port 端口号
     * @return this
     */
    public ServerBuilder port(int port) {
        if (this.setting == null) {
            this.setting = ServerSetting.defaults();
        }
        this.setting.setPort(port);
        return this;
    }

    /**
     * 设置监听地址。
     *
     * @param host 主机名或 IP
     * @return this
     */
    public ServerBuilder host(String host) {
        if (this.setting == null) {
            this.setting = ServerSetting.defaults();
        }
        this.setting.setHost(host);
        return this;
    }

    /**
     * 设置 ObjectContext 实例。
     *
     * <p>未设置时，Server 实现会自行创建默认的 {@link DefaultObjectContext}。
     * 通过本方法可以传入外部已初始化好的容器，实现多 Server 共享 Bean 定义。</p>
     *
     * @param objectContext IOC 上下文
     * @return this
     */
    public ServerBuilder objectContext(ObjectContext objectContext) {
        this.objectContext = objectContext;
        return this;
    }

    /**
     * 注册路由（指定 HTTP 方法）。
     *
     * @param path    路径
     * @param method  HTTP 方法
     * @param handler 处理器
     * @return this
     */
    public ServerBuilder mapping(String path, HttpMethod method, ServerHandler handler) {
        ensureServer();
        if (server instanceof AbstractServer abstractServer) {
            abstractServer.registerMapping(path, method, handler);
        } else {
            throw new UnsupportedOperationException("当前 Server 实现不支持 registerMapping: " + server.getClass().getName());
        }
        return this;
    }

    /**
     * 注册路由（不限 HTTP 方法）。
     *
     * @param path    路径
     * @param handler 处理器
     * @return this
     */
    public ServerBuilder mapping(String path, ServerHandler handler) {
        ensureServer();
        if (server instanceof AbstractServer abstractServer) {
            abstractServer.registerMapping(path, handler);
        } else {
            throw new UnsupportedOperationException("当前 Server 实现不支持 registerMapping: " + server.getClass().getName());
        }
        return this;
    }

    /**
     * 构建 Server 实例。
     *
     * @return Server
     */
    public Server build() {
        ensureServer();
        if (objectContext != null && server instanceof AbstractServer abstractServer) {
            abstractServer.setObjectContext(objectContext);
        }
        return server;
    }

    /** EnsureServer */
    private void ensureServer() {
        if (server == null) {
            if (setting == null) {
                setting = ServerSetting.defaults();
            }
            server = ServiceProvider.of(Server.class).getNewExtension(type, setting);
        }
    }
}
