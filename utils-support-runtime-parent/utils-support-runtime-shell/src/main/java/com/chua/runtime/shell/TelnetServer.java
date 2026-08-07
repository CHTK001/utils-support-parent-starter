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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telnet Shell 服务器 — 提供远程字符界面。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TelnetServer {

    private static final Logger LOG = Logger.getLogger(TelnetServer.class.getName());
    /**
     * 默认 Shell 端口
     */
    private static final int DEFAULT_PORT = 4567;

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
        if (!running.compareAndSet(false, true)) {
            LOG.log(Level.WARNING, "Shell 服务器已运行");
            return;
        }
        this.serverSocket = new ServerSocket(port);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "runtime-shell");
            t.setDaemon(true);
            return t;
        });
        LOG.log(Level.INFO, String.format("Telnet Shell 已启动，监听端口: %s", port));
        Thread acceptor = new Thread(this::acceptLoop, "runtime-shell-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * 接收连接循环。
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(new ShellSession(socket, registry));
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, String.format("接受连接异常: %s", e.getMessage()));
                }
            }
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
            LOG.log(Level.WARNING, String.format("关闭服务器异常", e));
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
     * 获取命令注册表（外部测试用）。
     *
     * @return CommandRegistry
     */
    public CommandRegistry getRegistry() {
        return registry;
    }
}