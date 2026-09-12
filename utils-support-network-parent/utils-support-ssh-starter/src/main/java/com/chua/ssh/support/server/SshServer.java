package com.chua.ssh.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ShellUrlServerFilter;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.handler.ServerHandlerFactory;
import com.chua.common.support.network.server.parser.ServerHandlerAnnotationParser;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.ssh.support.annotations.ShellMethod;
import com.chua.ssh.support.parser.ShellMethodServerHandlerParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.channel.WindowClosedException;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SSH Shell 服务器，继承 {@link AbstractServer}。
 * <p>使用 {@link ShellUrlServerFilter} 进行命令路由，
 * 支持通过 {@link ShellMethod} 注解声明式注册命令。</p>
 *
 * <pre>{@code
 * SshServer server = SshServer.builder()
 *     .port(7222)
 *     .password("secret")
 *     .build();
 * server.registerBean(new BuiltinShellCommands());
 * server.start();
 * }</pre>ShellCommands());
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SshServer extends AbstractServer {

    /**
     * 默认 SSH 端口
     */
    public static final int DEFAULT_PORT = 7222;

    /**
     * 连接密码，空字符串表示不验证
     */
    private final String password;

    /**
     * 空闲超时时间（秒），0 表示不限制
     */
    private final int idleTimeout;

    /**
     * Shell 命令路由过滤器
     */
    private final ShellUrlServerFilter shellFilter;

    /**
     * 处理器工厂，管理 Shell 命令路由
     */
    private final ServerHandlerFactory<ServerHandlerAnnotationParser> factory;

    /**
     * MINA SSHD 服务器实例
     */
    private org.apache.sshd.server.SshServer sshd;

    /**
     * 构造 SSH 服务器。
     *
     * @param setting     服务器配置
     * @param password    连接密码
     * @param idleTimeout 空闲超时
     */
    protected SshServer(ServerSetting setting, String password, int idleTimeout) {
        super(setting);
        this.password = password != null ? password : "";
        this.idleTimeout = idleTimeout;
        this.shellFilter = new ShellUrlServerFilter(objectContext);
        this.factory = shellFilter.getFactory();
        addFilter(shellFilter);
    }

    @Override
    /** 获取协议类型 */
    public ProtocolType getProtocolType() {
        return ProtocolType.SSH;
    }

    @Override
    /** 注册Bean */
    public Server registerBean(Object bean) {
        if (bean == null) {
            return this;
        }
        super.registerBean(bean);
        factory.initialize(ServerHandlerAnnotationParser.class, shellFilter);
        return this;
    }

    /**
     * 注册 Shell 命令。
     *
     * @param commandName 命令名称
     * @param handler     命令处理器
     * @return this
     */
    public SshServer registerCommand(String commandName, ServerHandler handler) {
        shellFilter.route(commandName, handler);
        return this;
    }

    /**
     * 注册 Shell 命令（含描述）。
     *
     * @param commandName 命令名称
     * @param description 命令描述
     * @param handler     命令处理器
     * @return this
     */
    public SshServer registerCommand(String commandName, String description, ServerHandler handler) {
        shellFilter.route(commandName, handler);
        return this;
    }

    /**
     * 移除 Shell 命令。
     *
     * @param commandName 命令名称
     * @return this
     */
    public SshServer removeCommand(String commandName) {
        shellFilter.removeRoute(commandName);
        return this;
    }

    /**
     * 获取所有已注册的命令名称。
     *
     * @return 命令名称列表
     */
    public List<String> getCommandNames() {
        return new ArrayList<>(shellFilter.getCommandNames());
    }

    @Override
    /** 执行开始 */
    protected void doStart() {
        try {
            sshd = org.apache.sshd.server.SshServer.setUpDefaultServer();
 // MINA 服务端必须配置主机密钥, 否则 检查配置 抛 主机键提供者 not 设置;
            // 未指定持久化路径时每次启动内存生成
            org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider keyProvider =
                    new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider();
            keyProvider.setAlgorithm("RSA");
            keyProvider.setKeySize(2048);
            sshd.setKeyPairProvider(keyProvider);
            sshd.setHost(setting.getHost());
            sshd.setPort(setting.getPort());
            sshd.setPasswordAuthenticator((username, password, session) -> {
                boolean ok = this.password.isEmpty() || this.password.equals(password);
                if (ok) {
                    log.info("SSH 用户认证成功: {}", username);
                } else {
                    log.warn("SSH 认证失败: {}", username);
                }
                return ok;
            });
            sshd.setShellFactory(new InteractiveShellFactory());
            sshd.start();
            log.info("SshServer 已启动: ssh://{}:{}", setting.getHost(), setting.getPort());
        } catch (Exception e) {
            throw new RuntimeException("SSH 服务器启动失败", e);
        }
    }

    @Override
    /** 执行停止 */
    protected void doStop() {
        try {
            if (sshd != null) {
                sshd.stop();
                sshd = null;
            }
        } catch (Exception e) {
            log.warn("SSH 服务器停止异常", e);
        }
    }

    /**
     * 交互式 Shell 会话，为每个 SSH 客户端处理命令输入。
     * @author CH
     * @since 4.0.0
     */
    private class InteractiveShell implements Command, Runnable {

        /**
         * 客户端输入流
         */
        private InputStream in;

        /**
         * 客户端输出流
         */
        private OutputStream out;

        /**
         * 客户端错误输出流
         */
        private OutputStream err;

        /**
         * 退出回调
         */
        private ExitCallback exitCallback;

        /**
         * 会话线程
         */
        private Thread thread;

        @Override
        /** 设置输入流 */
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        /** 设置输出流 */
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        /** 设置记录错误流 */
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        /** 设置exitcallback */
        public void setExitCallback(ExitCallback callback) {
            this.exitCallback = callback;
        }

        @Override
        /** 开始 */
        public void start(ChannelSession channel, Environment env) throws IOException {
            thread = new Thread(this, "ssh-shell-" + channel.getSession().getIoSession().getRemoteAddress());
            thread.start();
        }

        @Override
        /** 销毁 */
        public void destroy(ChannelSession channel) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        /** 运行 */
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {
                writer.println("Welcome to SshServer");
                writer.println("Type 'help' for available commands, 'exit' to quit.");
                writer.flush();

                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                        writer.println("Bye~");
                        break;
                    }
                    if ("clear".equalsIgnoreCase(trimmed)) {
                        writer.print("\u001b[H\u001b[2J");
                        writer.flush();
                        continue;
                    }
                    if ("help".equalsIgnoreCase(trimmed)) {
                        printHelp(writer);
                        continue;
                    }
                    SshCommandRequest request = new SshCommandRequest(trimmed);
                    SshCommandResponse response = new SshCommandResponse(out);
                    handleRequest(request, response);
                }
                exitCallback.onExit(0);
            } catch (WindowClosedException e) {
                exitCallback.onExit(0);
            } catch (IOException e) {
                try {
                    err.write(("IO error: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
                exitCallback.onExit(1);
            }
        }

        /**
         * 打印命令帮助信息。
         *
         * @param writer 输出写入器
         */
        private void printHelp(PrintWriter writer) {
            writer.println("Available commands:");
            writer.println("  help              显示帮助信息");
            writer.println("  exit / quit       退出 Shell");
            writer.println("  clear             清屏");
            writer.println();
            writer.println("Use 'help <command>' for more info on a specific command.");
            writer.flush();
        }
    }

    /**
     * Shell 工厂，为每个会话创建交互式 Shell。
     * @author CH
     * @since 4.0.0
     */
    private class InteractiveShellFactory implements ShellFactory {

        @Override
        /** 创建Shell */
        public Command createShell(ChannelSession channel) throws IOException {
            return new InteractiveShell();
        }
    }

    /**
      * 创建 ssh服务端 构建器。
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
      * ssh服务端 构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {

        /**
         * 监听端口
         */
        private int port = DEFAULT_PORT;

        /**
         * 连接密码
         */
        private String password = "";

        /**
         * 绑定地址
         */
        private String host = "0.0.0.0";

        /**
         * 空闲超时
         */
        private int idleTimeout;

        /**
         * 设置监听端口。
         *
         * @param port 端口号
         * @return this
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置连接密码。
         *
         * @param password 密码字符串
         * @return this
         */
        public Builder password(String password) {
            this.password = password != null ? password : "";
            return this;
        }

        /**
         * 设置绑定地址。
         *
         * @param host 主机地址
         * @return this
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * 设置空闲超时时间。
         *
         * @param seconds 超时秒数
         * @return this
         */
        public Builder idleTimeout(int seconds) {
            this.idleTimeout = seconds;
            return this;
        }

        /**
          * 构建 ssh服务端 实例。
         *
         * @return SshServer 实例
         */
        public SshServer build() {
            ServerSetting setting = ServerSetting.defaults();
            setting.setHost(host);
            setting.setPort(port);
            return new SshServer(setting, password, idleTimeout);
        }
    }

    /**
     * 命令行入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String password = "";
        String host = "0.0.0.0";

        for (int i = 0; i < args.length; i++) {
            if (("--port".equals(args[i]) || "-p".equals(args[i])) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (("--password".equals(args[i]) || "-P".equals(args[i])) && i + 1 < args.length) {
                password = args[++i];
            } else if (("--host".equals(args[i]) || "-h".equals(args[i])) && i + 1 < args.length) {
                host = args[++i];
            }
        }

        try {
            SshServer server = SshServer.builder()
                    .host(host)
                    .port(port)
                    .password(password)
                    .build();
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        } catch (Exception e) {
            log.error("启动 SshServer 失败", e);
            System.exit(1);
        }
    }
}
