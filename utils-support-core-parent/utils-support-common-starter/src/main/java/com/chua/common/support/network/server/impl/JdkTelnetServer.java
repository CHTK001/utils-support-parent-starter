package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullUnmarked;

/**
 * 基于 JDK 的 Telnet 服务器实现。
 *
 * <p>支持 Telnet 协议协商、命令处理、会话管理。
 * 内置命令处理器，可通过 registerCommand 注册自定义命令。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * ServerSetting setting = ServerSetting.defaults();
 * setting.setPort(2323);
 * JdkTelnetServer server = new JdkTelnetServer(setting);
 *
 * // 注册命令
 * server.registerCommand("help", (session, args) -> {
 *     session.println("可用命令: help, whoami, time, echo");
 * });
 * server.registerCommand("whoami", (session, args) -> {
 *     session.println("你是: " + session.getClientId());
 * });
 * server.registerCommand("time", (session, args) -> {
 *     session.println("当前时间: " + java.time.LocalDateTime.now());
 * });
 * server.registerCommand("echo", (session, args) -> {
 *     session.println(String.join(" ", args));
 * });
 *
 * server.start();
 * }</pre>
 *
 * <h2>Telnet 客户端连接</h2>
 * <pre>{@code
 * telnet 127.0.0.1 2323
 * > help
 * 可用命令: help, whoami, time, echo
 * > whoami
 * 你是: /127.0.0.1:54321
 * > time
 * 当前时间: 2026-07-18T21:00:00
 * > echo Hello Telnet
 * Hello Telnet
 * </pre>
 *
 * @author CH
 * @since 2026/07/18
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Slf4j
@Spi({"jdk-telnet"})
public class JdkTelnetServer extends AbstractServer {

    /** Telnet 协议选项：回显 */
    private static final int TELNET_OPTION_ECHO = 1;
    /** Telnet 协议选项：抑制回显 */
    private static final int TELNET_OPTION_SGA = 3;
    /** Telnet 协议选项：窗口大小 */
    private static final int TELNET_OPTION_NAWS = 31;

    /** Telnet 协议命令：IAC */
    private static final int TELNET_IAC = 255;
    /** Telnet 协议命令：WILL */
    private static final int TELNET_WILL = 251;
    /** Telnet 协议命令：WONT */
    private static final int TELNET_WONT = 252;
    /** Telnet 协议命令：DO */
    private static final int TELNET_DO = 253;
    /** Telnet 协议命令：DONT */
    private static final int TELNET_DONT = 254;

    private ServerSocket serverSocket;
    private ExecutorService workerPool;
    private final Map<String, TelnetCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, TelnetSession> sessions = new ConcurrentHashMap<>();

    public JdkTelnetServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    protected void doStart() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(setting.getHost(), setting.getPort()), setting.getBacklog());
            workerPool = Executors.newFixedThreadPool(setting.getWorkerThreads());
            running = true;

            // 内置命令
            registerBuiltinCommands();

            workerPool.submit(this::acceptLoop);
            log.info("JDK TelnetServer started on {}:{}", setting.getHost(), setting.getPort());
        } catch (IOException e) {
            throw new RuntimeException("Telnet 服务器启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        running = false;
        // 关闭所有会话
        for (TelnetSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            log.info("JDK TelnetServer stopped");
        }
        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(setting.getShutdownQuietPeriod(), TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                workerPool.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    log.error("接受连接异常", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        String clientId = socket.getRemoteSocketAddress().toString();
        log.info("Telnet 连接: {}", clientId);

        TelnetSession session;
        try {
            session = new TelnetSession(clientId, socket);
        } catch (IOException e) {
            log.error("创建会话失败: {}", e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        sessions.put(clientId, session);

        try {
            // Telnet 协商
            session.negotiate();

            // 发送欢迎消息
            session.println("=== Telnet Server ===");
            session.println("输入 help 查看可用命令");
            session.print("> ");

            // 命令循环
            while (running && !socket.isClosed()) {
                String line = session.readLine();
                if (line == null) {
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    session.print("> ");
                    continue;
                }

                // 解析命令
                String[] parts = line.split("\\s+", 2);
                String cmdName = parts[0].toLowerCase();
                String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];

                // 查找并执行命令
                TelnetCommand cmd = commands.get(cmdName);
                if (cmd != null) {
                    try {
                        cmd.execute(session, args);
                    } catch (Exception e) {
                        session.println("错误: " + e.getMessage());
                    }
                } else {
                    session.println("未知命令: " + cmdName + "，输入 help 查看帮助");
                }

                session.print("> ");
            }
        } catch (Exception e) {
            log.debug("Telnet 会话异常: {}", e.getMessage());
        } finally {
            sessions.remove(clientId);
            session.close();
            log.info("Telnet 断开: {}", clientId);
        }
    }

    private void registerBuiltinCommands() {
        registerCommand("help", (session, args) -> {
            session.println("可用命令:");
            for (String name : commands.keySet()) {
                session.println("  " + name);
            }
        });

        registerCommand("whoami", (session, args) -> {
            session.println("会话ID: " + session.getClientId());
        });

        registerCommand("time", (session, args) -> {
            session.println("时间: " + java.time.LocalDateTime.now());
        });

        registerCommand("echo", (session, args) -> {
            session.println(String.join(" ", args));
        });

        registerCommand("quit", (session, args) -> {
            session.println("再见!");
            session.close();
        });
    }

    /**
     * 注册 Telnet 命令。
     *
     * @param name    命令名称
     * @param command 命令处理器
     */
    public JdkTelnetServer registerCommand(String name, TelnetCommand command) {
        commands.put(name.toLowerCase(), command);
        return this;
    }

    /**
     * 获取所有活跃会话。
     *
     * @return 会话 ID → 会话对象
     */
    public Map<String, TelnetSession> getSessions() {
        return Map.copyOf(sessions);
    }

    /**
     * 获取会话数量。
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * 广播消息到所有会话。
     *
     * @param message 消息内容
     */
    public void broadcast(String message) {
        for (TelnetSession session : sessions.values()) {
            try {
                session.println(message);
            } catch (Exception e) {
                log.debug("广播消息失败: {}", e.getMessage());
            }
        }
    }

    /**
     * Telnet 命令接口。
     */
    @FunctionalInterface
    public interface TelnetCommand {
        /**
         * 执行命令。
         *
         * @param session 当前会话
         * @param args    命令参数
         */
        void execute(TelnetSession session, String[] args) throws Exception;
    }

    /**
     * Telnet 会话，封装客户端 Socket 和读写操作。
     */
    public static class TelnetSession {
        /**
         * 客户端 ID
         */
        private final String clientId;
        private final Socket socket;
        private final BufferedReader reader;
        private final DataOutputStream writer;

        TelnetSession(String clientId, Socket socket) throws IOException {
            this.clientId = clientId;
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new DataOutputStream(socket.getOutputStream());
        }

        /**
         * Telnet 协商（纯文本模式，不发送控制序列）。
         *
         * <p>直接使用原始文本模式，避免 Telnet 协商字节干扰 UTF-8 编码。</p>
         */
        void negotiate() throws IOException {
            // 纯文本模式，不发送任何协商字节
        }

        /**
         * 读取一行（过滤 Telnet 控制序列，保留 UTF-8 文本）。
         */
        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int b = reader.read();
                if (b == -1) {
                    return null;
                }

                // 跳过 Telnet IAC 序列
                if (b == TELNET_IAC) {
                    int cmd = reader.read();
                    if (cmd == TELNET_WILL || cmd == TELNET_WONT ||
                        cmd == TELNET_DO || cmd == TELNET_DONT) {
                        // reader.read(); // 跳过选项字节
               
                    }
                    continue;
                }

                // 换行符
                if (b == '\n') {
                    break;
                }
                if (b == '\r') {
                    continue;
                }

                // 跳过其他控制字符
                if (b < 0x20 && b != '\t') {
                    continue;
                }

                sb.append((char) b);
            }
            return sb.toString().trim();
        }

        /**
         * 输出文本（UTF-8 编码）。
         */
        public void print(String text) throws IOException {
            writer.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            writer.flush();
        }

        /**
         * 输出文本并换行（UTF-8 编码）。
         */
        public void println(String text) throws IOException {
            writer.write((text + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            writer.flush();
        }

        /**
         * 获取客户端 ID。
         */
        public String getClientId() {
            return clientId;
        }

        /**
         * 关闭会话。
         */
        public void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }

        /**
         * 判断会话是否活跃。
         */
        public boolean isActive() {
            return socket != null && !socket.isClosed() && socket.isConnected();
        }
    }
}
