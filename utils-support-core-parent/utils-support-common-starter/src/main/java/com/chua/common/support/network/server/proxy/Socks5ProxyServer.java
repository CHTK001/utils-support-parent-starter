package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于原生 JDK {@link ServerSocket} 的 SOCKS5 代理服务器。
 *
 * <p>实现 RFC 1928 标准协议，支持：</p>
 * <ul>
 *     <li>无认证（{@code 0x00}）与用户名/口令认证（{@code 0x02}）</li>
 *     <li>{@code CONNECT} 命令（IPv4、域名、IPv6）</li>
 *     <li>{@code BIND} 与 {@code UDP ASSOCIATE} 命令框架（仅占位，详细处理可在子类扩展）</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * Socks5ProxyServer server = new Socks5ProxyServer(setting, "user", "pass");
 * server.start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"socks5-proxy"})
public class Socks5ProxyServer extends AbstractServer {

    /**
     * SOCKS5 协议版本号。
     */
    protected static final byte VERSION = 0x05;

    /**
     * 认证方式：无认证。
     */
    protected static final byte METHOD_NO_AUTH = 0x00;

    /**
     * 认证方式：用户名/口令。
     */
    protected static final byte METHOD_USER_PASS = 0x02;

    /**
     * 认证方式：无支持的方法。
     */
    protected static final byte METHOD_NO_ACCEPTABLE = (byte) 0xFF;

    /**
     * 用户名/口令认证子协议版本。
     */
    protected static final byte USER_PASS_VERSION = 0x01;

    /**
     * 用户名/口令认证状态：成功。
     */
    protected static final byte USER_PASS_OK = 0x00;

    /**
     * 用户名/口令认证状态：失败。
     */
    protected static final byte USER_PASS_FAIL = 0x01;

    /**
     * 命令类型：CONNECT。
     */
    protected static final byte CMD_CONNECT = 0x01;

    /**
     * 命令类型：BIND。
     */
    protected static final byte CMD_BIND = 0x02;

    /**
     * 命令类型：UDP ASSOCIATE。
     */
    protected static final byte CMD_UDP_ASSOCIATE = 0x03;

    /**
     * 地址类型：IPv4。
     */
    protected static final byte ATYP_IPV4 = 0x01;

    /**
     * 地址类型：域名。
     */
    protected static final byte ATYP_DOMAIN = 0x03;

    /**
     * 地址类型：IPv6。
     */
    protected static final byte ATYP_IPV6 = 0x04;

    /**
     * 应答状态：成功。
     */
    protected static final byte REP_SUCCESS = 0x00;

    /**
     * 应答状态：通用失败。
     */
    protected static final byte REP_GENERAL_FAILURE = 0x01;

    /**
     * 应答状态：规则不允许。
     */
    protected static final byte REP_NOT_ALLOWED = 0x02;

    /**
     * 应答状态：网络不可达。
     */
    protected static final byte REP_NETWORK_UNREACHABLE = 0x03;

    /**
     * 应答状态：主机不可达。
     */
    protected static final byte REP_HOST_UNREACHABLE = 0x04;

    /**
     * 应答状态：连接被拒绝。
     */
    protected static final byte REP_REFUSED = 0x05;

    /**
     * 应答状态：TTL 过期。
     */
    protected static final byte REP_TTL_EXPIRED = 0x06;

    /**
     * 应答状态：命令不支持。
     */
    protected static final byte REP_CMD_UNSUPPORTED = 0x07;

    /**
     * 应答状态：地址类型不支持。
     */
    protected static final byte REP_ATYP_UNSUPPORTED = 0x08;

    /**
     * 用户名（可选）。
     */
    protected final String username;

    /**
     * 口令（可选）。
     */
    protected final String password;

    /**
     * 后端连接超时（毫秒）。
     */
    protected final int connectTimeoutMs;

    /**
     * IO 读取超时（毫秒）。
     */
    protected final int readTimeoutMs;

    /**
     * 虚拟线程池。
     */
    protected final ExecutorService proxyPool = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 服务运行状态。
     */
    protected final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 活跃连接计数。
     */
    protected final AtomicInteger activeConnections = new AtomicInteger(0);

    /**
     * JDK 服务端监听套接字。
     */
    protected ServerSocket serverSocket;

    /**
     * 构造 SOCKS5 代理服务器（无认证）。
     *
     * @param setting 服务器配置
     */
    public Socks5ProxyServer(ServerSetting setting) {
        this(setting, null, null);
    }

    /**
     * 构造 SOCKS5 代理服务器（支持用户名/口令认证）。
     *
     * @param setting  服务器配置
     * @param username 用户名，null 表示仅支持无认证
     * @param password 口令
     */
    public Socks5ProxyServer(ServerSetting setting, String username, String password) {
        this(setting, username, password, 5000, 30000);
    }

    /**
     * 构造 SOCKS5 代理服务器（完整参数）。
     *
     * @param setting          服务器配置
     * @param username         用户名
     * @param password         口令
     * @param connectTimeoutMs 后端连接超时（毫秒）
     * @param readTimeoutMs    IO 读取超时（毫秒）
     */
    public Socks5ProxyServer(ServerSetting setting, String username, String password,
                             int connectTimeoutMs, int readTimeoutMs) {
        super(setting);
        this.username = username;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 注册服务器过滤器。
     *
     * @param filter 要注册的过滤器
     * @return 当前服务器实例
     */
    @Override
    public Socks5ProxyServer addFilter(ServerFilter filter) {
        super.addFilter(filter);
        return this;
    }

    /**
     * 获取当前活跃连接数。
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return activeConnections.get();
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    @Override
    protected void doStart() {
        try {
            InetSocketAddress addr = new InetSocketAddress(setting.getHost(), setting.getPort());
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(setting.isSoReuseAddr());
            serverSocket.bind(addr, setting.getBacklog());
            // 回填实际端口（port=0 时由系统分配）
            setting.setPort(serverSocket.getLocalPort());
            running.set(true);
            proxyPool.submit(this::acceptLoop);
            boolean authEnabled = username != null && !username.isEmpty();
            log.info("Socks5ProxyServer 启动成功：{}://{}:{}, auth={}",
                    setting.getProtocol(), setting.getHost(), setting.getPort(),
                    authEnabled ? "user/pass" : "none");
        } catch (IOException e) {
            throw new RuntimeException("Socks5ProxyServer 启动失败", e);
        }
    }

    @Override
    protected void doStop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        log.info("Socks5ProxyServer 已停止：{}://{}:{}", setting.getProtocol(), setting.getHost(), setting.getPort());
    }

    /**
     * 接受连接循环。
     */
    protected void acceptLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                try {
                    proxyPool.submit(() -> handleConnection(clientSocket));
                } catch (Exception e) {
                    log.warn("SOCKS5 任务被拒绝: {}", e.getMessage());
                    try {
                        clientSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("SOCKS5 接受连接异常", e);
                }
            }
        }
    }

    /**
     * 处理单个客户端连接。
     *
     * @param clientSocket 客户端套接字
     */
    protected void handleConnection(Socket clientSocket) {
        InetSocketAddress remote = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
        activeConnections.incrementAndGet();
        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {
            clientSocket.setSoTimeout(readTimeoutMs);
            if (!negotiateAuth(in, out)) {
                return;
            }
            Socks5Request request = readRequest(in);
            if (request == null) {
                return;
            }
            handleCommand(clientSocket, in, out, request);
        } catch (Exception e) {
            log.debug("[socks5] 处理异常: {}", e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
            log.debug("[socks5] 连接关闭: {}", remote);
            activeConnections.decrementAndGet();
        }
    }

    /**
     * 协商认证方式。
     *
     * @param in  客户端输入流
     * @param out 客户端输出流
     * @return true 表示认证成功，可以继续后续流程
     * @throws IOException IO 异常
     */
    protected boolean negotiateAuth(InputStream in, OutputStream out) throws IOException {
        int ver = in.read();
        if (ver != VERSION) {
            return false;
        }
        int nMethods = in.read();
        if (nMethods <= 0) {
            return false;
        }
        byte[] methods = readBytes(in, nMethods);
        boolean authEnabled = username != null && !username.isEmpty();
        if (authEnabled) {
            boolean clientSupportsUserPass = false;
            for (byte m : methods) {
                if (m == METHOD_USER_PASS) {
                    clientSupportsUserPass = true;
                    break;
                }
            }
            if (!clientSupportsUserPass) {
                out.write(new byte[]{VERSION, METHOD_NO_ACCEPTABLE});
                out.flush();
                return false;
            }
            out.write(new byte[]{VERSION, METHOD_USER_PASS});
            out.flush();
            return doUserPassAuth(in, out);
        }
        boolean clientSupportsNoAuth = false;
        for (byte m : methods) {
            if (m == METHOD_NO_AUTH) {
                clientSupportsNoAuth = true;
                break;
            }
        }
        if (!clientSupportsNoAuth) {
            out.write(new byte[]{VERSION, METHOD_NO_ACCEPTABLE});
            out.flush();
            return false;
        }
        out.write(new byte[]{VERSION, METHOD_NO_AUTH});
        out.flush();
        return true;
    }

    /**
     * 执行用户名/口令认证。
     *
     * @param in  客户端输入流
     * @param out 客户端输出流
     * @return true 表示认证通过
     * @throws IOException IO 异常
     */
    protected boolean doUserPassAuth(InputStream in, OutputStream out) throws IOException {
        int ver = in.read();
        if (ver != USER_PASS_VERSION) {
            return false;
        }
        int ulen = in.read();
        if (ulen <= 0) {
            return false;
        }
        byte[] uBytes = readBytes(in, ulen);
        int plen = in.read();
        if (plen <= 0) {
            return false;
        }
        byte[] pBytes = readBytes(in, plen);
        String u = new String(uBytes, java.nio.charset.StandardCharsets.UTF_8);
        String p = new String(pBytes, java.nio.charset.StandardCharsets.UTF_8);
        boolean ok = username.equals(u) && (password == null ? password == null : password.equals(p));
        out.write(new byte[]{USER_PASS_VERSION, ok ? USER_PASS_OK : USER_PASS_FAIL});
        out.flush();
        return ok;
    }

    /**
     * 读取客户端请求。
     *
     * @param in 客户端输入流
     * @return 解析后的请求对象
     * @throws IOException IO 异常
     */
    protected Socks5Request readRequest(InputStream in) throws IOException {
        int ver = in.read();
        int cmd = in.read();
        int rsv = in.read();
        if (ver != VERSION || rsv != 0x00) {
            return null;
        }
        int atyp = in.read();
        InetSocketAddress target;
        switch (atyp) {
            case ATYP_IPV4:
                byte[] ipv4 = readBytes(in, 4);
                int port = readPort(in);
                try {
                    target = new InetSocketAddress(InetAddress.getByAddress(ipv4), port);
                } catch (UnknownHostException e) {
                    target = null;
                }
                break;
            case ATYP_DOMAIN:
                int len = in.read();
                if (len <= 0) {
                    return null;
                }
                byte[] domainBytes = readBytes(in, len);
                String domain = new String(domainBytes, java.nio.charset.StandardCharsets.UTF_8);
                int port2 = readPort(in);
                target = new InetSocketAddress(domain, port2);
                break;
            case ATYP_IPV6:
                byte[] ipv6 = readBytes(in, 16);
                int port3 = readPort(in);
                try {
                    target = new InetSocketAddress(InetAddress.getByAddress(ipv6), port3);
                } catch (UnknownHostException e) {
                    target = null;
                }
                break;
            default:
                return null;
        }
        return new Socks5Request((byte) cmd, target);
    }

    /**
     * 处理 SOCKS5 命令。
     *
     * @param clientSocket 客户端套接字
     * @param in           客户端输入流
     * @param out          客户端输出流
     * @param request      请求对象
     * @throws IOException IO 异常
     */
    protected void handleCommand(Socket clientSocket, InputStream in, OutputStream out,
                                 Socks5Request request) throws IOException {
        if (request.command == CMD_CONNECT) {
            handleConnect(clientSocket, out, request.target);
        } else {
            writeReply(out, REP_CMD_UNSUPPORTED, new InetSocketAddress(0));
        }
    }

    /**
     * 处理 CONNECT 命令。
     *
     * @param clientSocket 客户端套接字
     * @param out          客户端输出流
     * @param target       目标地址
     * @throws IOException IO 异常
     */
    protected void handleConnect(Socket clientSocket, OutputStream out, InetSocketAddress target) throws IOException {
        if (target == null || target.isUnresolved()) {
            writeReply(out, REP_HOST_UNREACHABLE, new InetSocketAddress(0));
            return;
        }
        Socket backend;
        try {
            backend = new Socket();
            backend.connect(target, connectTimeoutMs);
            backend.setSoTimeout(readTimeoutMs);
        } catch (IOException e) {
            byte rep;
            if (e instanceof java.net.ConnectException) {
                rep = REP_REFUSED;
            } else if (e instanceof java.net.NoRouteToHostException) {
                rep = REP_HOST_UNREACHABLE;
            } else {
                rep = REP_NETWORK_UNREACHABLE;
            }
            writeReply(out, rep, new InetSocketAddress(0));
            return;
        }
        writeReply(out, REP_SUCCESS, new InetSocketAddress(0));
        forwardBidirectional(clientSocket, backend);
    }

    /**
     * 写入 SOCKS5 应答。
     *
     * @param out        客户端输出流
     * @param rep        应答状态码
     * @param bindAddr   绑定的本地地址
     * @throws IOException IO 异常
     */
    protected void writeReply(OutputStream out, byte rep, InetSocketAddress bindAddr) throws IOException {
        out.write(new byte[]{VERSION, rep, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    /**
     * 双向转发。
     *
     * @param clientSocket 客户端套接字
     * @param backendSocket 后端套接字
     */
    protected void forwardBidirectional(Socket clientSocket, Socket backendSocket) {
        Thread c2b = Thread.ofVirtual()
                .name("socks5-c2b-" + clientSocket.getPort())
                .start(() -> {
                    try {
                        forward(clientSocket.getInputStream(), backendSocket.getOutputStream());
                    } catch (IOException e) {
                        log.debug("[socks5] 获取 c2b 流失败: {}", e.getMessage());
                    }
                });
        Thread b2c = Thread.ofVirtual()
                .name("socks5-b2c-" + clientSocket.getPort())
                .start(() -> {
                    try {
                        forward(backendSocket.getInputStream(), clientSocket.getOutputStream());
                    } catch (IOException e) {
                        log.debug("[socks5] 获取 b2c 流失败: {}", e.getMessage());
                    }
                });
        try {
            c2b.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        b2c.interrupt();
    }

    /**
     * 单向转发。
     *
     * @param in  源输入流
     * @param out 目标输出流
     */
    protected void forward(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (Exception e) {
            if (running.get()) {
                log.debug("[socks5] 转发结束: {}", e.getMessage());
            }
        }
    }

    /**
     * 读取指定字节数。
     *
     * @param in    输入流
     * @param count 字节数
     * @return 字节数组
     * @throws IOException IO 异常
     */
    protected byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(bytes, offset, count - offset);
            if (n == -1) {
                throw new IOException("连接提前关闭");
            }
            offset += n;
        }
        return bytes;
    }

    /**
     * 读取 2 字节端口号（网络字节序）。
     *
     * @param in 输入流
     * @return 端口号
     * @throws IOException IO 异常
     */
    protected int readPort(InputStream in) throws IOException {
        byte[] port = readBytes(in, 2);
        return ((port[0] & 0xFF) << 8) | (port[1] & 0xFF);
    }

    /**
     * SOCKS5 客户端请求。
     *
     * @param command 命令类型
     * @param target  目标地址
     */
    public record Socks5Request(byte command, InetSocketAddress target) {
    }
}
