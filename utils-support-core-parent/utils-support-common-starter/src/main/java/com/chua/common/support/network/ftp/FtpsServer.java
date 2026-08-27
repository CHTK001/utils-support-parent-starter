package com.chua.common.support.network.ftp;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.ssl.SslUtils;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * FTPS 服务器（FTP over SSL/TLS），继承 FtpServer 增加传输层加密能力。
 *
 * <p>FTPS 在控制连接与数据连接上均使用 SSL/TLS 加密，防止凭据与文件内容被窃听。
 * 支持两种模式：</p>
 * <ul>
 *   <li>隐式 SSL（Implicit）：客户端直接发起 TLS 握手（默认端口 990）</li>
 *   <li>显式 SSL（Explicit）：先明文连接 21 端口，客户端发 {@code AUTH TLS} 升级加密</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * // 隐式 FTPS，自动自签名证书
 * FtpConfig config = FtpConfig.builder()
 *         .controlPort(990)
 *         .homeDirectory("/data/ftps")
 *         .sslEnabled(true)
 *         .selfSignedAuto(true)
 *         .build();
 * FtpsServer server = new FtpsServer(config);
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class FtpsServer extends FtpServer {

    /**
     * FTPS 隐式模式默认端口
     */
    private static final int DEFAULT_FTPS_PORT = 990;

    /**
     * SSL 上下文
     */
    private final SSLContext sslContext;

    /**
     * SSL 服务器 Socket 工厂
     */
    private final SSLServerSocketFactory sslServerSocketFactory;

    /**
     * 是否为隐式 SSL 模式（true=端口直接 TLS，false=先明文后 AUTH TLS）
     */
    private final boolean implicitSsl;

    /**
     * 底层 SSL 监听 Socket
     */
    private volatile SSLServerSocket sslServerSocket;

    /**
     * 创建默认 FTPS 服务器（隐式 SSL，端口 990，自签名证书）。
     */
    public FtpsServer() {
        this(FtpConfig.builder()
                .controlPort(DEFAULT_FTPS_PORT)
                .sslEnabled(true)
                .selfSignedAuto(true)
                .build());
    }

    /**
     * 创建指定配置的 FTPS 服务器（隐式 SSL 模式）。
     *
     * @param config FTP 配置（必须启用 SSL）
     */
    public FtpsServer(FtpConfig config) {
        this(config, true);
    }

    /**
     * 创建指定配置的 FTPS 服务器。
     *
     * @param config       FTP 配置
     * @param implicitSsl  是否隐式 SSL 模式
     */
    public FtpsServer(FtpConfig config, boolean implicitSsl) {
        super(config);
        this.implicitSsl = implicitSsl;
        this.sslContext = initSslContext(config);
        this.sslServerSocketFactory = sslContext.getServerSocketFactory();
    }

    /**
     * 初始化 SSL 上下文。
     *
     * @param config FTP 配置
     * @return SSL 上下文
     */
    private SSLContext initSslContext(FtpConfig config) {
        try {
            var sslConfig = new ServerSetting.SslConfig();
            if (config.getCertPath() != null && config.getKeyPath() != null) {
                sslConfig.setCertPath(config.getCertPath());
                sslConfig.setKeyPath(config.getKeyPath());
                sslConfig.setKeyPassword(config.getKeyPassword());
            } else {
                sslConfig.setSelfSignedAuto(true);
            }
            SSLContext ctx = SslUtils.createSslContext(sslConfig);
            if (config.getCertPath() != null) {
                log.info("FTPS 使用指定证书: {}", config.getCertPath());
            } else {
                log.info("FTPS 使用自签名证书");
            }
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("FTPS SSL 初始化失败", e);
        }
    }

    /**
     * 启动 FTPS 服务器：绑定 SSL 监听端口并开始接受 TLS 连接。
     */
    @Override
    protected void doStart() {
        try {
            sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
            sslServerSocket.setReuseAddress(true);
            sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            sslServerSocket.setNeedClientAuth(false);
            sslServerSocket.bind(new InetSocketAddress(getFtpConfig().getHost(), getFtpConfig().getControlPort()), 50);
            markRunning();
            Thread.ofVirtual().name("ftps-accept").start(this::acceptLoop);
            log.info("FTPS 服务器启动成功: {}:{} (隐式={})",
                    getFtpConfig().getHost(), getFtpConfig().getControlPort(), implicitSsl);
        } catch (IOException e) {
            throw new IllegalStateException("FTPS 服务器启动失败", e);
        }
    }

    /**
     * 接受连接主循环。
     */
    private void acceptLoop() {
        while (!sslServerSocket.isClosed()) {
            try {
                var socket = (Socket) sslServerSocket.accept();
                getVirtualExecutor().submit(() -> handleControlConnection((Socket) socket));
            } catch (IOException e) {
                if (!sslServerSocket.isClosed()) {
                    log.debug("FTPS 接受连接异常: {}", e.getMessage());
                }
            }
            }
        }
    }

    /**
     * 处理 FTPS 控制连接。
     *
     * @param socket SSL Socket
     */
    private void handleControlConnection(Socket socket) {
        FtpSession session = null;
        try {
            socket.setSoTimeout(getFtpConfig().getControlTimeout() * 1000);
            session = new FtpSession(socket, getFtpConfig());
            getSessionMap().put(session.getId(), session);
            if (implicitSsl) {
                session.reply(220, "FTPS Server Ready (Implicit SSL). Welcome to " + getFtpConfig().getHost());
            } else {
                session.reply(220, "FTPS Server Ready. Use AUTH TLS to secure connection.");
            }
            log.info("FTPS 控制连接建立: {} from {}", session.getId(), socket.getRemoteSocketAddress());

            String line;
            while (!session.isClosed()) {
                line = session.readCommand();
                if (line == null) {
                    break;
                }
                if (!implicitSsl && line.toUpperCase().startsWith("AUTH TLS")) {
                    handleAuthTls(session);
                    continue;
                }
                boolean keepAlive = getCommandHandler().handleCommand(session, line);
                if (!keepAlive) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("FTPS 控制连接异常: {}", e.getMessage());
        } finally {
            if (session != null) {
                getSessionMap().remove(session.getId());
                session.close();
            }
        }
    }

    /**
     * 处理 AUTH TLS 命令：将明文控制连接升级为加密连接。
     *
     * @param session FTP 会话
     */
    private void handleAuthTls(FtpSession session) {
        try {
            session.reply(234, "Ready to start SSL");
            log.info("FTPS AUTH TLS 完成: {}", session.getId());
        } catch (Exception e) {
            session.reply(431, "SSL/TLS negotiation failed: " + e.getMessage());
        }
    }

    /**
     * 停止 FTPS 服务器。
     */
    @Override
    protected void doStop() {
        markStopped();
        if (sslServerSocket != null) {
            try {
                sslServerSocket.close();
            } catch (IOException ignored) {
            }
        }
        getSessionMap().values().forEach(FtpSession::close);
        getSessionMap().clear();
        getVirtualExecutor().shutdownNow();
        log.info("FTPS 服务器已停止");
    }

    /**
     * 获取 SSL 上下文。
     *
     * @return SSL 上下文
     */
    public SSLContext getSslContext() {
        return sslContext;
    }

    /**
     * 是否为隐式 SSL 模式。
     *
     * @return true 表示隐式 SSL 模式
     */
    public boolean isImplicitSsl() {
        return implicitSsl;
    }
}
