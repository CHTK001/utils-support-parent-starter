package com.chua.common.support.network.server.proxy;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * 基于原生 JDK {@link java.net.ServerSocket} 的 SOCKS5 代理服务器。
 *
 * <p>继承 {@link AbstractProxyServer}，自动获得多 acceptor 并行、Semaphore 连接限流、
 * TCP_NODELAY、32KB ThreadLocal 转发缓冲、CompletableFuture 双向转发等高并发基础设施。</p>
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
public class Socks5ProxyServer extends AbstractProxyServer {

    // ==================== SOCKS5 协议常量 ====================

    protected static final byte VERSION = 0x05;
    protected static final byte METHOD_NO_AUTH = 0x00;
    protected static final byte METHOD_USER_PASS = 0x02;
    protected static final byte METHOD_NO_ACCEPTABLE = (byte) 0xFF;
    protected static final byte USER_PASS_VERSION = 0x01;
    protected static final byte USER_PASS_OK = 0x00;
    protected static final byte USER_PASS_FAIL = 0x01;
    protected static final byte CMD_CONNECT = 0x01;
    protected static final byte CMD_BIND = 0x02;
    protected static final byte CMD_UDP_ASSOCIATE = 0x03;
    protected static final byte ATYP_IPV4 = 0x01;
    protected static final byte ATYP_DOMAIN = 0x03;
    protected static final byte ATYP_IPV6 = 0x04;
    protected static final byte REP_SUCCESS = 0x00;
    protected static final byte REP_GENERAL_FAILURE = 0x01;
    protected static final byte REP_NOT_ALLOWED = 0x02;
    protected static final byte REP_NETWORK_UNREACHABLE = 0x03;
    protected static final byte REP_HOST_UNREACHABLE = 0x04;
    protected static final byte REP_REFUSED = 0x05;
    protected static final byte REP_TTL_EXPIRED = 0x06;
    protected static final byte REP_CMD_UNSUPPORTED = 0x07;
    protected static final byte REP_ATYP_UNSUPPORTED = 0x08;

    // ==================== 实例字段 ====================

    protected final String username;
    protected final String password;
    protected final int connectTimeoutMs;
    protected final int readTimeoutMs;

    // ==================== 构造函数 ====================

    public Socks5ProxyServer(ServerSetting setting) {
        this(setting, null, null);
    }

    public Socks5ProxyServer(ServerSetting setting, String username, String password) {
        this(setting, username, password, 5000, 30000);
    }

    public Socks5ProxyServer(ServerSetting setting, String username, String password,
                             int connectTimeoutMs, int readTimeoutMs) {
        super(setting);
        this.username = username;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public Socks5ProxyServer addFilter(ServerFilter filter) {
        super.addFilter(filter);
        return this;
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.TCP;
    }

    // ==================== 连接处理 ====================

    /**
     * 处理单个客户端连接：SOCKS5 认证协商 → 读取请求 → 建立后端连接 → 双向转发。
     * <p>复用父类 {@link AbstractProxyServer#forwardBidirectional} 进行高效双向传输。</p>
     */
    @Override
    protected void handleConnection(Socket clientSocket) {
        InetSocketAddress remote = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
        try {
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();
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
            closeQuietly(clientSocket);
            log.debug("[socks5] 连接关闭: {}", remote);
        }
    }

    // ==================== SOCKS5 协议实现 ====================

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
            boolean supports = false;
            for (byte m : methods) {
                if (m == METHOD_USER_PASS) {
                    supports = true;
                    break;
                }
            }
            if (!supports) {
                out.write(new byte[]{VERSION, METHOD_NO_ACCEPTABLE});
                out.flush();
                return false;
            }
            out.write(new byte[]{VERSION, METHOD_USER_PASS});
            out.flush();
            return doUserPassAuth(in, out);
        }
        boolean supports = false;
        for (byte m : methods) {
            if (m == METHOD_NO_AUTH) {
                supports = true;
                break;
            }
        }
        if (!supports) {
            out.write(new byte[]{VERSION, METHOD_NO_ACCEPTABLE});
            out.flush();
            return false;
        }
        out.write(new byte[]{VERSION, METHOD_NO_AUTH});
        out.flush();
        return true;
    }

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
        boolean ok = username.equals(u) && (password == null ? p == null : password.equals(p));
        out.write(new byte[]{USER_PASS_VERSION, ok ? USER_PASS_OK : USER_PASS_FAIL});
        out.flush();
        return ok;
    }

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
                try { target = new InetSocketAddress(InetAddress.getByAddress(ipv4), port); }
                catch (UnknownHostException e) { target = null; }
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
                try { target = new InetSocketAddress(InetAddress.getByAddress(ipv6), port3); }
                catch (UnknownHostException e) { target = null; }
                break;
            default:
                return null;
        }
        return new Socks5Request((byte) cmd, target);
    }

    protected void handleCommand(Socket clientSocket, InputStream in, OutputStream out,
                                 Socks5Request request) throws IOException {
        if (request.command == CMD_CONNECT) {
            handleConnect(clientSocket, out, request.target);
        } else {
            writeReply(out, REP_CMD_UNSUPPORTED, new InetSocketAddress(0));
        }
    }

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

    protected void writeReply(OutputStream out, byte rep, InetSocketAddress bindAddr) throws IOException {
        out.write(new byte[]{VERSION, rep, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    /**
     * SOCKS5 客户端请求。
     */
    public record Socks5Request(byte command, InetSocketAddress target) {
    }
}
