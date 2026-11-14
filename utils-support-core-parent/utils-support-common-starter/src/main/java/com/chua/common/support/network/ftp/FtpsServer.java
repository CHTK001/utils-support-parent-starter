// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.ftp;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.ssl.SslUtils;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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
     * 底层监听 Socket
     *
     * <p>隐式模式是 {@code SSLServerSocket}（端口即 TLS），显式模式是普通
     * {@code ServerSocket}（先明文回 220，客户端 {@code AUTH TLS} 后再升级）。</p>
     */
    private volatile ServerSocket listenSocket;

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
     * 创建使用外部服务器配置的 FTPS 服务器（隐式 SSL + 自动自签证书）。
     *
     * <p>SPI 实例化按「实参运行时类型」精确匹配构造器，服务管理传入的是
     * {@link ServerSetting}。本类原先只有无参与 {@link FtpConfig} 两种构造器，
     * 匹配失败后实例化抛错被 SPI 吞掉、只表现为返回 {@code null}，
     * 最终报「{@code Server SPI 未注册: ftps}」——而 {@code /protocols} 仍在宣告该协议，
     * 属于「宣告支持但实际建不出来」。</p>
     *
     * @param setting 外部服务器配置（host 与 port 生效，其余取 FTPS 默认值）
     */
    public FtpsServer(ServerSetting setting) {
        this(buildConfigFrom(setting), true);
    }

    /**
     * 由外部服务器配置派生 FTPS 配置：覆盖监听地址与控制端口，并强制开启 SSL 与自签。
     *
     * @param setting 外部服务器配置
     * @return FTPS 配置
     */
    private static FtpConfig buildConfigFrom(ServerSetting setting) {
        return FtpConfig.builder()
                .host(setting.getHost())
                .controlPort(setting.getPort())
                .sslEnabled(true)
                .selfSignedAuto(true)
                .build();
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
            // 必须先做预处理：selfSignedAuto 只是「意图」，prepareSslConfig 才把它翻成
            // enabled=true + selfSigned=true。缺了这一步，loadKeyStore 走不到
            // generateSelfSignedKeyStore 分支，直接抛「未配置 KeyStore 与 Cert/Key 文件」，
            // 表现为 FTPS 一启动就失败（SPI 还会把构造异常吞成 null，报成「SPI 未注册」）。
            SslUtils.prepareSslConfig(sslConfig);
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
     * 启动 FTPS 服务器。
     *
     * <p>隐式模式直接绑定 {@link SSLServerSocket}，端口即 TLS；显式模式必须绑定
     * 普通 {@link java.net.ServerSocket}，先明文回 220，等客户端 {@code AUTH TLS}
     * 再原地升级。若显式模式也用 SSLServerSocket，服务端写 banner 会立刻触发
     * 握手，而客户端还在等 banner —— 双方互等直到超时。</p>
     */
    @Override
    protected void doStart() {
        try {
            ServerSocket candidate;
            if (implicitSsl) {
                SSLServerSocket sslSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
                sslSocket.setEnabledProtocols(FtpSession.TLS_PROTOCOLS);
                sslSocket.setNeedClientAuth(false);
                candidate = sslSocket;
            } else {
                candidate = new ServerSocket();
            }
            candidate.setReuseAddress(true);
            // backlog 与 FtpServer 保持同一口径（统一由 ServerSetting#resolveBacklog 收敛）
            candidate.bind(new InetSocketAddress(getFtpConfig().getHost(), getFtpConfig().getControlPort()),
                    setting.resolveBacklog(128));
            listenSocket = candidate;
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
        ServerSocket listener = listenSocket;
        while (!listener.isClosed()) {
            try {
                var socket = listener.accept();
                // 与 FtpServer 同口径：先过接纳闸门与连接预算，再派发（FTPS 无 503 语义）
                if (!accepting) {
                    // 优雅关闭中：不再接纳新连接（存量连接继续服务）
                    socket.close();
                    continue;
                }
                if (!tryAcquireConnection()) {
                    log.warn("FTPS 连接数超限 (max={})，拒绝 {}", setting.getMaxConnections(),
                            socket.getRemoteSocketAddress());
                    socket.close();
                    continue;
                }
                try {
                    applySocketOptions(socket);
                    getVirtualExecutor().submit(() -> handleControlConnection(socket));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // 停止中任务被拒：连接不会被处理，必须在这里归还预算
                    socket.close();
                    releaseConnection();
                }
            } catch (IOException e) {
                if (!listener.isClosed()) {
                    log.debug("FTPS 接受连接异常: {}", e.getMessage());
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
            if (implicitSsl) {
                // 隐式模式整条链路都是 TLS，数据连接同样加密
                session.enableDataTls(sslServerSocketFactory);
            }
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
            } else {
                // 会话构造失败时连接仍由本方法持有，必须在这里关掉（与 FtpServer 一致）
                try {
                    socket.close();
                } catch (IOException e) {
                    log.debug("关闭 FTPS 控制连接失败，已忽略: {}", e.getMessage());
                }
            }
            // 连接预算必须与 accept 期的 tryAcquireConnection() 严格配对
            releaseConnection();
        }
    }

    /**
     * 处理 AUTH TLS 命令：将明文控制连接升级为加密连接。
     *
     * <p>RFC 4217 要求先回 {@code 234}，再在**同一条** TCP 连接上直接开始 TLS 握手。
     * 原实现只回 {@code 234} 而没有任何升级动作，客户端随后的 ClientHello 会被当成
     * 命令行解析（握手必失败），显式 FTPS 实际完全不可用。</p>
     *
     * @param session FTP 会话
     */
    private void handleAuthTls(FtpSession session) {
        session.reply(234, "Ready to start SSL");
        Socket plain = session.getControlSocket();
        try {
            // 服务端模式：包装的是 accept 出来的普通 socket，autoClose 让关闭只关一层
            SSLSocket tlsSocket = (SSLSocket) sslContext.getSocketFactory()
                    .createSocket(plain, null, plain.getPort(), true);
            tlsSocket.setUseClientMode(false);
            tlsSocket.setEnabledProtocols(FtpSession.TLS_PROTOCOLS);
            session.upgradeControlToTls(tlsSocket);
            // 主动握手：失败要在这里暴露，不能等到读命令时才表现为「连接莫名断开」
            tlsSocket.startHandshake();
            // 控制连接已加密，数据连接随之加密（RFC 4217 的 PROT P）
            session.enableDataTls(sslServerSocketFactory);
            log.info("FTPS AUTH TLS 完成: {} cipher={}", session.getId(),
                    tlsSocket.getSession().getCipherSuite());
        } catch (Exception e) {
            // 半升级的连接既不能继续明文命令也无法恢复，只能关闭
            log.warn("FTPS AUTH TLS 升级失败，连接将关闭: {}", e.getMessage());
            session.close();
        }
    }

    /**
     * 停止 FTPS 服务器。
     */
    @Override
    protected void doStop() {
        markStopped();
        ServerSocket listener = listenSocket;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException e) {
                log.debug("关闭 FTPS 监听端口失败，已忽略: {}", e.getMessage());
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