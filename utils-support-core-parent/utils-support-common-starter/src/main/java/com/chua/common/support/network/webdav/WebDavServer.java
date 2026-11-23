package com.chua.common.support.network.webdav;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.ServiceProvider;
import lombok.Getter;
import lombok.ToString;

import java.io.File;

/**
 * WebDAV 服务器。
 *
 * <p>HTTP 传输层经 SPI 装配：按 {@link WebDavConfig#getServerType()}（默认 {@code jdk}，
 * 即 {@code com.chua.common.support.network.server.impl.JdkHttpServer}）创建协议服务器，
 * 本类只负责 WebDAV 语义 —— 通过 {@link WebDavFileHandler} 在传输层过滤器链上提供
 * WebDAV 方法集：OPTIONS/GET/HEAD/PUT/DELETE/MKCOL/PROPFIND/PROPPATCH/COPY/MOVE/LOCK/UNLOCK。
 * 存储为文件系统根目录，与 {@link com.chua.common.support.network.ftp.FtpServer} 同风格：
 * Builder/配置构建，{@code start()}/{@code stop()} 生命周期由 {@link AbstractServer}
 * 统一管理并委托给传输层。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * WebDavServer server = new WebDavServer(WebDavConfig.builder()
 *         .port(8080)
 *         .homeDirectory(new File("webdav-home"))
 *         .user("alice", "secret")
 *         .build());
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Getter
@ToString
public class WebDavServer extends AbstractServer {

    /**
     * WebDAV 配置
     */
    private final WebDavConfig webDavConfig;

    /**
     * HTTP 传输层委托（SPI 装配的协议服务器）
     */
    private final Server transport;

    /**
     * 创建使用默认配置的 WebDAV 服务器。
     */
    public WebDavServer() {
        this(WebDavConfig.defaults());
    }

    /**
     * 创建使用指定配置的 WebDAV 服务器。
     *
     * @param config WebDAV 配置
     */
    public WebDavServer(WebDavConfig config) {
        this(buildServerSetting(config), config);
    }

    /**
     * 创建使用外部服务器配置的 WebDAV 服务器。
     *
     * <p>服务管理以 SPI 方式装配服务器时，实例化按「实参运行时类型」精确匹配构造器，
     * 传入的是 {@link ServerSetting}（host 与 port 生效，其余 WebDAV 项取默认值）。</p>
     *
     * @param setting 外部服务器配置
     */
    public WebDavServer(ServerSetting setting) {
        this(setting, buildConfigFrom(setting));
    }

    /**
     * 内部构造：绑定传输配置与 WebDAV 配置，装配 HTTP 传输层并注册 WebDAV 处理器。
     *
     * @param setting 服务器配置
     * @param config  WebDAV 配置
     */
    private WebDavServer(ServerSetting setting, WebDavConfig config) {
        super(setting);
        this.webDavConfig = config;
        // 与 ServerBuilder 内部同一 SPI 装配入口；直接传入本服务器的 ServerSetting，
        // 使 protocol/readTimeout/writeTimeout 等配置与端口回填对传输层同样生效
        this.transport = ServiceProvider.of(Server.class).getNewExtension(config.getServerType(), setting);
        registerWebDavHandler(transport, config);
    }

    /**
     * 在传输层过滤器链上注册 WebDAV 处理器。
     *
     * @param transport 传输层服务器
     * @param config    WebDAV 配置
     */
    private static void registerWebDavHandler(Server transport, WebDavConfig config) {
        if (transport instanceof AbstractServer abstractServer) {
            abstractServer.addReactiveFilter(new WebDavFileHandler(config));
            return;
        }
        throw new IllegalStateException("WebDAV 传输层实现不支持响应式过滤器: "
                + transport.getClass().getName() + "（serverType=" + config.getServerType() + "）");
    }

    /**
     * 由外部服务器配置派生 WebDAV 配置：只覆盖监听地址与端口。
     *
     * @param setting 外部服务器配置
     * @return WebDAV 配置
     */
    private static WebDavConfig buildConfigFrom(ServerSetting setting) {
        return WebDavConfig.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .build();
    }

    /**
     * 构建服务器配置。
     *
     * @param config WebDAV 配置
     * @return 服务器配置
     */
    private static ServerSetting buildServerSetting(WebDavConfig config) {
        ServerSetting setting = ServerSetting.defaults();
        setting.setHost(config.getHost());
        setting.setPort(config.getPort());
        setting.setProtocol("webdav");
        setting.setReadTimeout(config.getReadTimeout() * 1000);
        setting.setWriteTimeout(config.getWriteTimeout() * 1000);
        return setting;
    }

    /**
     * 获取存储根目录。
     *
     * @return 根目录
     */
    public File getHomeDirectory() {
        return webDavConfig.getHomeDirectory();
    }

    /**
     * 启动 HTTP 传输层。
     */
    @Override
    protected void doStart() {
        transport.start();
    }

    /**
     * 停止 HTTP 传输层。
     */
    @Override
    protected void doStop() {
        transport.stop();
    }

    /**
     * 获取ProtocolType
     */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.HTTP;
    }
}
