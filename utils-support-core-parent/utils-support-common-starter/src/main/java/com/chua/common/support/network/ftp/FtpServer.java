package com.chua.common.support.network.ftp;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.TcpServer;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
* FTP 服务器，基于 AbstractServer 生命周期管理与 TcpServer 接口契约实现。
*
* <p>FTP 协议采用双通道架构：控制连接（默认端口 21）用于命令/响应交互，
* 数据连接用于实际的文件传输（被动模式 PASV 或主动模式 PORT）。</p>
*
* <p>架构设计说明：FTP 与帧式/纯流式协议不同，需要直接访问底层 Socket 对象
* （PASV 模式需获取服务器本地 IP、主动模式需连接客户端指定地址），
* 因此本服务器在 {@code doStart()} 中自管理 {@link ServerSocket}，而非委托 JdkTcpServer。
* 仍继承 {@link AbstractServer} 获得统一生命周期、过滤器与指标能力，
* 并实现 {@link TcpServer} 接口满足协议无关服务器契约。</p>
*
* <h2>用法</h2>
* <pre>{@code
* // 默认配置（端口 21，匿名只读）
* FtpServer server = new FtpServer();
* server.start();
*
* // 自定义配置
* FtpConfig config = FtpConfig.builder()
*         .controlPort(2121)
*         .homeDirectory("/data/ftp")
*         .anonymousEnabled(true)
*         .anonymousWriteEnabled(false)
*         .build();
* FtpServer server = new FtpServer(config);
* server.start();
* }</pre>
*
* @author CH
* @since 4.0.0.43
 */
@Slf4j
@Getter
public class FtpServer extends AbstractServer implements TcpServer {

    /**
    * FTP 默认控制端口
    */
    private static final int DEFAULT_CONTROL_PORT = 21;

    /**
    * 控制连接接受线程名称前缀
    */
    private static final String ACCEPT_THREAD_NAME = "ftp-accept";

    /**
    * FTP 配置
    */
    private final FtpConfig ftpConfig;

    /**
    * 底层控制连接监听 Socket
    */
    private volatile ServerSocket controlServerSocket;

    /**
    * 命令处理器（无状态，可复用）
    */
    private final FtpCommandHandler commandHandler = new FtpCommandHandler();

    /**
    * 活跃会话表（clientId -> 会话）
    */
    private final Map<String, FtpSession> sessions = new ConcurrentHashMap<>();

    /**
    * 虚拟线程执行器（每连接一虚拟线程，承载控制连接生命周期）
    */
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
    * 接受连接线程
    */
    private volatile Thread acceptThread;

    /**
    * 是否正在运行
    */
    @Getter
    private volatile boolean running;

    /**
    * 创建使用默认配置的 FTP 服务器。
    */
    public FtpServer() {
        this(FtpConfig.defaults());
    }

    /**
    * 创建使用指定配置的 FTP 服务器。
    *
    * @param config FTP 配置
    */
    public FtpServer(FtpConfig config) {
        super(buildServerSetting(config));
        this.ftpConfig = config;
        ensureHomeDirectory();
    }

    /**
    * 构建服务器配置（供 AbstractServer 初始化生命周期/过滤器/指标）。
    *
    * @param config FTP 配置
    * @return 服务器配置
    */
    private static ServerSetting buildServerSetting(FtpConfig config) {
        var setting = ServerSetting.defaults();
        setting.setHost(config.getHost());
        setting.setPort(config.getControlPort());
        setting.setProtocol("ftp");
        setting.setReadTimeout(config.getControlTimeout() * 1000);
        setting.setWriteTimeout(config.getControlTimeout() * 1000);
        return setting;
    }

    /**
    * 确保根目录存在，不存在则创建。
    */
    private void ensureHomeDirectory() {
        var homeDir = ftpConfig.getHomeDirectory();
        if (!homeDir.exists() && !homeDir.mkdirs()) {
            log.warn("FTP 根目录创建失败: {}", homeDir.getAbsolutePath());
        }
    }

    /**
    * 启动 FTP 服务器：绑定控制端口并开始接受连接。
    */
    @Override
    protected void doStart() {
        try {
            controlServerSocket = new ServerSocket();
            controlServerSocket.setReuseAddress(true);
            controlServerSocket.bind(new InetSocketAddress(ftpConfig.getHost(), ftpConfig.getControlPort()), 50);
            setting.setPort(controlServerSocket.getLocalPort());
            running = true;
            acceptThread = Thread.ofVirtual().name(ACCEPT_THREAD_NAME).start(this::acceptLoop);
            log.info("FTP 服务器启动成功: {}:{}", ftpConfig.getHost(), setting.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("FTP 服务器启动失败: " + ftpConfig.getHost() + ":" + ftpConfig.getControlPort(), e);
        }
    }

    /**
    * 接受连接主循环：每个控制连接派发到独立虚拟线程处理。
    */
    private void acceptLoop() {
        while (running && !controlServerSocket.isClosed()) {
            try {
                var socket = controlServerSocket.accept();
                socket.setSoTimeout(ftpConfig.getControlTimeout() * 1000);
                virtualExecutor.submit(() -> handleControlConnection(socket));
            } catch (IOException e) {
                if (running) {
                    log.debug("FTP 接受连接异常: {}", e.getMessage());
                }
            }
        }
    }

    /**
    * 处理控制连接：读取 FTP 命令、执行命令处理器、维护会话生命周期。
    *
    * @param socket 控制连接 Socket
    */
    private void handleControlConnection(Socket socket) {
        FtpSession session = null;
        try {
            session = new FtpSession(socket, ftpConfig);
            sessions.put(session.getId(), session);
            session.reply(220, "FTP Server Ready. Welcome to " + ftpConfig.getHost());
            log.info("FTP 控制连接建立: {} from {}", session.getId(), socket.getRemoteSocketAddress());

            String line;
            while (running && !session.isClosed()) {
                line = session.readCommand();
                if (line == null) {
                    break;
                }
                boolean keepAlive = commandHandler.handleCommand(session, line);
                if (!keepAlive) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                log.debug("FTP 控制连接异常: {}", e.getMessage());
            }
        } finally {
            if (session != null) {
                sessions.remove(session.getId());
                session.close();
            }
            log.debug("FTP 控制连接关闭");
        }
    }

    /**
    * 停止 FTP 服务器：关闭所有会话与监听端口。
    */
    @Override
    protected void doStop() {
        running = false;
        sessions.values().forEach(FtpSession::close);
        sessions.clear();
        virtualExecutor.shutdownNow();
        if (controlServerSocket != null) {
            try {
                controlServerSocket.close();
            } catch (IOException ignored) {
            }
        }
        log.info("FTP 服务器已停止");
    }

    /**
    * 获取协议类型。
    *
    * @return TCP 协议类型
    */
    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    /**
    * 获取监听端口。
    *
    * @return 控制端口
    */
    @Override
    public int getPort() {
        return setting.getPort();
    }

    /**
    * 注册帧处理器（TcpServer 接口方法，FTP 为流式文本协议，此方法为空实现）。
    *
    * @param handler 帧处理器
    * @return 当前实例
    */
    @Override
    public FtpServer setHandler(TcpServerHandler handler) {
        return this;
    }

    /**
    * 获取活跃会话数量。
    *
    * @return 会话数
    */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    // ==================== 供子类 FtpsServer 访问 ====================

    /**
    * 获取活跃会话表（供子类访问）。
    *
    * @return 会话表
    */
    protected Map<String, FtpSession> getSessionMap() {
        return sessions;
    }

    /**
    * 获取命令处理器（供子类访问）。
    *
    * @return 命令处理器
    */
    protected FtpCommandHandler getCommandHandler() {
        return commandHandler;
    }

    /**
    * 获取虚拟线程执行器（供子类访问）。
    *
    * @return 执行器
    */
    protected ExecutorService getVirtualExecutor() {
        return virtualExecutor;
    }

    /**
    * 设置服务器运行标志（供子类控制生命周期）。
    */
    protected void markRunning() {
        this.running = true;
    }

    /**
    * 标记服务器停止（供子类控制生命周期）。
    */
    protected void markStopped() {
        this.running = false;
    }
}
