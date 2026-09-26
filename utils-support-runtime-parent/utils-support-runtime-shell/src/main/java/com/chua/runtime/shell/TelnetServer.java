package com.chua.runtime.shell;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.command.CommandRegistry;
import com.chua.runtime.shell.command.builtin.ApmCommand;
import com.chua.runtime.shell.command.builtin.HelpCommand;
import com.chua.runtime.shell.command.builtin.StatusCommand;
import com.chua.runtime.shell.command.builtin.InfoCommand;
import com.chua.runtime.shell.command.builtin.ThreadsCommand;
import com.chua.runtime.shell.command.builtin.MemoryCommand;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telnet Shell 服务器 — 提供远程字符界面。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TelnetServer {

    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(TelnetServer.class.getName());
    /**
     * 默认 Shell 端口
     */
    private static final int DEFAULT_PORT = 4567;

    /**
     * 默认监听地址：Shell 无鉴权，只允许本机
     */
    private static final String DEFAULT_BIND = "127.0.0.1";

    /**
     * 默认最大并发会话
     */
    private static final int DEFAULT_MAX_SESSIONS = 8;

    /**
     * 默认会话空闲超时（毫秒）
     */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 300_000L;

    /**
     * 监听地址
     */
    private String bindAddress = DEFAULT_BIND;

    /**
     * 最大并发会话数
     */
    private int maxSessions = DEFAULT_MAX_SESSIONS;

    /**
     * 会话空闲超时（毫秒）
     */
    private long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MS;

    /**
     * 会话并发闸门
     */
    private Semaphore sessions = new Semaphore(DEFAULT_MAX_SESSIONS);

    /**
     * 服务端套接字
     */
    private ServerSocket serverSocket;

    /**
     * 命令注册表
     */
    private final CommandRegistry registry;

    /**
     * APM 启动器（可选，用于 apm 命令动态展示）
     */
    private ApmBootstrap apm;

    /**
     * 线程池
     */
    private ExecutorService executor;

    /**
     * 运行状态
     */
    private final AtomicBoolean running;

    /**
     * 创建 Telnet 服务器（无 APM 集成）。
     */
    public TelnetServer() {
        this.registry = new CommandRegistry();
        this.running = new AtomicBoolean(false);
        registerDefaults();
    }

    /**
     * 创建 Telnet 服务器，绑定 APM 启动器。
     *
     * @param apm APM 启动器
     */
    public TelnetServer(ApmBootstrap apm) {
        this.registry = new CommandRegistry();
        this.apm = apm;
        this.running = new AtomicBoolean(false);
        registerDefaults();
    }

    /**
     * 注册默认内置命令。
     */
    private void registerDefaults() {
        registry.register(new HelpCommand(registry));
        registry.register(new StatusCommand());
        registry.register(new InfoCommand());
        registry.register(new ThreadsCommand());
        registry.register(new MemoryCommand());
        // APM 命令动态注册（如果 apm 可用）
        if (apm != null) {
            registry.register(new ApmCommand(apm));
        }
    }

    /**
     * 注册自定义命令。
     *
     * @param command 命令
     */
    public void register(Command command) {
        registry.register(command);
    }

    /**
     * 启动服务器。
     *
     * @param port 端口
     * @throws IOException 启动异常
     */
    public void start(int port) throws IOException {
        start(bindAddress, port);
    }

    /**
     * 启动服务器（指定监听地址）。
     *
     * <p>Shell 无任何鉴权，可执行 {@code runtime start/stop}、线程与内存查看、日志 tail，
     * 因此默认只绑回环；显式传入非回环地址时会记录告警，由部署方自行保证网络隔离。</p>
     *
     * @param host 监听地址（如 {@code 127.0.0.1}、{@code 0.0.0.0}）
     * @param port 端口
     * @throws IOException 启动异常
     */
    public void start(String host, int port) throws IOException {
        if (!running.compareAndSet(false, true)) {
            LOG.log(Level.WARNING, "Shell 服务器已运行");
            return;
        }
        InetAddress bind = InetAddress.getByName(host == null || host.isBlank() ? DEFAULT_BIND : host);
        this.bindAddress = host;
        this.serverSocket = new ServerSocket(port, 16, bind);
        this.sessions = new Semaphore(maxSessions);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "runtime-shell");
            t.setDaemon(true);
            return t;
        });
        if (!bind.isLoopbackAddress()) {
            LOG.log(Level.WARNING, "Telnet Shell 监听在非回环地址 {0}:{1}，该端口无鉴权，请自行确保网络隔离",
                    new Object[]{bind.getHostAddress(), port});
        }
        LOG.log(Level.INFO, "Telnet Shell 已启动，监听地址: " + bind.getHostAddress() + ":" + port
                + "，最大并发会话: " + maxSessions);
        Thread acceptor = new Thread(this::acceptLoop, "runtime-shell-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * 限制最大并发会话数。
     *
     * @param maxSessions 并发上限，至少 1
     * @return 当前服务器
     */
    public TelnetServer withMaxSessions(int maxSessions) {
        this.maxSessions = Math.max(1, maxSessions);
        return this;
    }

    /**
     * 设置会话空闲超时。
     *
     * @param idleTimeoutMillis 毫秒，{@code <=0} 表示不限时
     * @return 当前服务器
     */
    public TelnetServer withIdleTimeout(long idleTimeoutMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        return this;
    }

    /**
     * 设置监听地址（供 {@link #start(int)} 使用）。
     *
     * @param host 监听地址
     * @return 当前服务器
     */
    public TelnetServer withBind(String host) {
        this.bindAddress = host;
        return this;
    }

    /**
     * 接收连接循环。
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (!sessions.tryAcquire()) {
                    reject(socket, "会话已达上限，请稍后重试");
                    continue;
                }
                executor.submit(() -> serve(socket));
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "接受连接异常: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 会话包装：设置空闲超时并归还并发许可。
     *
     * @param socket 客户端连接
     */
    private void serve(Socket socket) {
        try {
            if (idleTimeoutMillis > 0) {
                socket.setSoTimeout((int) idleTimeoutMillis);
            }
            new ShellSession(socket, registry).run();
        } catch (Exception e) {
            LOG.log(Level.FINE, "会话异常: " + e.getMessage(), e);
        } finally {
            closeQuietly(socket);
            sessions.release();
        }
    }

    /**
     * 拒绝并关闭连接
     *
     * @param socket 客户端连接
     * @param reason 拒绝原因
     */
    private static void reject(Socket socket, String reason) {
        try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println(reason);
        } catch (IOException e) {
            LOG.log(Level.FINE, "拒绝会话时写入失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 静默关闭连接
     *
     * @param socket 客户端连接
     */
    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "关闭连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 停止服务器。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "关闭服务器异常", e);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        LOG.log(Level.INFO, "Telnet Shell 已停止");
    }

    /**
     * 是否运行中。
     *
     * @return 运行中返回 true
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 实际监听地址。
     *
     * @return 监听地址，未启动时返回 {@code null}
     */
    public InetAddress getLocalAddress() {
        ServerSocket current = serverSocket;
        return current == null ? null : current.getInetAddress();
    }

    /**
     * 实际监听端口。
     *
     * @return 端口，未启动时返回 {@code -1}
     */
    public int getLocalPort() {
        ServerSocket current = serverSocket;
        return current == null ? -1 : current.getLocalPort();
    }

    /**
     * 获取命令注册表（外部测试用）。
     *
     * @return CommandRegistry
     */
    public CommandRegistry getRegistry() {
        return registry;
    }
}