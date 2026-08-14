package com.chua.gateway.server.bridge;

import com.chua.gateway.server.store.Connection;
import com.chua.gateway.server.tunnel.GatewayTunnel;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

/**
 * RDP / VNC / SSH 协议桥接器（经 guacd 子进程）。
 *
 * <p>浏览器侧使用 {@code @guacamole/client}（HTML5 client）。
 * 连接流程：浏览器 → WS frame ↔ 桥接器 ↔ guacd TCP :4822 ↔ guacd ↔ RDP/VNC/SSH 服务器。</p>
 *
 * <p>本类负责：
 *   <ol>
 *     <li>{@link #connect()} 建立到 guacd :4822 的 TCP socket</li>
 *     <li>{@link #writeSelectInstruction()} 发 Guacamole {@code select} 指令（协议 + host/port/user/pass）
 *         —— 必须由服务端发，否则浏览器侧无法开始会话</li>
 *     <li>后续 {@link #writeToRemote}/{@link #readFromRemote()} 透传客户端 ↔ guacd 帧</li>
 *   </ol>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GuacamoleBridge implements RemoteBridge {

    /**
     * 默认 guacd 端口（与 application.properties 中 gateway.guacd.port 默认值一致）
     */
    public static final int DEFAULT_GUACD_PORT = 4822;

    /**
     * guacd 读取超时（毫秒）
     */
    private static final int GUACD_READ_TIMEOUT_MS = 1000;

    /**
     * guacd "ready" 等待超时（毫秒）
     */
    private static final long GUACD_READY_TIMEOUT_MS = 5000L;

    /**
     * 默认 screen width（用于 size 指令）
     */
    private static final String DEFAULT_WIDTH = "1024";

    /**
     * 默认 screen height
     */
    private static final String DEFAULT_HEIGHT = "768";

    /**
     * 底层连接
     */
    private final Connection connection;

    /**
     * guacd host（默认 127.0.0.1，本地子进程）
     */
    private final String guacdHost;

    /**
     * guacd port
     */
    private final int guacdPort;

    /**
     * 到 guacd 的 TCP socket
     */
    private volatile Socket socket;

    public GuacamoleBridge(Connection connection, String guacdHost, int guacdPort) {
        this.connection = connection;
        this.guacdHost = guacdHost;
        this.guacdPort = guacdPort;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public void connect() throws Exception {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        log.info("[gateway-server] Guacamole 连接: guacd={}:{} target={}:{}",
                guacdHost, guacdPort, connection.host(), connection.port());
        socket = new Socket(guacdHost, guacdPort);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(GUACD_READ_TIMEOUT_MS);
        log.info("[gateway-server] Guacamole socket 建立: target={}:{}", connection.host(), connection.port());
    }

    @Override
    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception e) {
                log.warn("[gateway-server] Guacamole socket 关闭失败: {}", e.getMessage());
            }
        }
        socket = null;
    }

    @Override
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * 返回到 guacd 的 TCP socket（供 WS handler 透传）。
     *
     * @return Socket 实例，未连接返回 {@code null}
     */
    public Socket socket() {
        return socket;
    }

    /**
     * 向 guacd 发 Guacamole {@code select} 指令（必须在 {@link #connect()} 后立刻调用）。
     *
     * <p>标准 guacamole 流程：本方法<b>只发 {@code select,<protocol>}</b>（不带连接参数），
     * guacd 收到后回 {@code args.<connectId>.<protocol>.<...参数名列表...>;} 指令，
     * 浏览器（guacamole-common-js）收到 args 后才发 {@code size} + {@code connect}
     * （connect 携带 host/port/user/password 等完整参数），guacd 随后 connect 并回 {@code ready}。</p>
     *
     * @throws IOException 写入失败
     */
    public void writeSelectInstruction() throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        Connection c = connection;
        String protocol = c.protocol() == null ? "rdp" : c.protocol().toLowerCase();
        // guacd 只接受 rdp / vnc / ssh
        if (!"rdp".equals(protocol) && !"vnc".equals(protocol) && !"ssh".equals(protocol)) {
            log.warn("[gateway-server] Guacamole 未知协议: {}, 强制 vnc", protocol);
            protocol = "vnc";
        }

        StringBuilder sb = new StringBuilder();
        appendElement(sb, "select");
        appendElement(sb, protocol);
        // 结束符（最后一个 element 后用 ;）
        sb.setCharAt(sb.length() - 1, ';');

        String inst = sb.toString();
        OutputStream out = socket.getOutputStream();
        out.write(inst.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
        log.info("[gateway-server] Guacamole select 指令已发: {}", inst);
    }

    /**
     * 把 {@code length.value,} 追加到 sb。
     */
    private static void appendElement(StringBuilder sb, String value) {
        sb.append(value.length()).append('.').append(value).append(',');
    }

    /**
     * 在 select 后读取 guacd 返回的连接参数指令（args 指令），
     * 转成二进制帧供 WS handler 转发给浏览器（guacamole-common-js）。
     *
     * <p>guacd 收到 select 后会回一条 args 指令：
     * {@code args.<connectId>.<protocol>.<width>.<height>.<dpi>.<audio>.<video>...;}
     * （例如 {@code 4.args,8.1,4.rdp,5.1024,5.768} 风格）。浏览器只有收到该指令
     * 才知道会话已建立并进入数据模式。</p>
     *
     * @return 完整的 args 指令字节（含终止符 {@code ;}）；超时或失败返回 {@code null}
     */
    public byte[] readInstructionForClientWhileConnecting() {
        try {
            String inst = readGuacdInstruction(GUACD_READY_TIMEOUT_MS);
            if (inst == null || inst.isEmpty()) {
                return null;
            }
            // 补回终止符（readGuacdInstruction 去掉了 ;）
            return (inst + ";").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[gateway-server] 读取 guacd args 指令失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在 select 后自动完成 {@code size} + {@code connect} 握手，建立到被控主机的会话。
     *
     * <p>标准 guacamole 流程中，浏览器（guacamole-common-js）收到 {@code args} 后
     * 会发 {@code size} 和 {@code connect}。但 guacamole-common-js 的 {@code Client}
     * 不自动处理 {@code args}，需要应用层填参数。为简化，本方法由网关侧直接完成
     * 握手（网关知道 host/port/user/password），浏览器只需 {@code client.connect()}
     * 建立 WS 并接收 {@code ready} 与后续数据流。</p>
     *
     * <p>流程：读 args → 发 {@code size,<w>,<h>} → 发 {@code connect,<version>,<38 个 SSH 参数>}。</p>
     *
     * @throws IOException 读取或写入失败
     */
    public void autoConnect() throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        // 1. 读 args（guacd 返回参数名列表）
        String argsInst = readGuacdInstruction(GUACD_READY_TIMEOUT_MS);
        if (argsInst == null || argsInst.isEmpty()) {
            throw new IOException("guacd 未返回 args 指令");
        }
        // 2. 发 size（guacamole-common-js 用 size,<w>,<h> 两个参数）
        writeInstruction("size", DEFAULT_WIDTH, DEFAULT_HEIGHT);
        // 3. 发 connect：connect,<protocol_version>,<按 args 顺序的参数值>
        //    args 指令格式：args,<VERSION>,<param1>,<param2>,...
        //    第一个元素是 "args"，第二个是协议版本，其余是参数名。
        String[] elems = parseInstructionElements(argsInst);
        if (elems.length < 3) {
            throw new IOException("args 指令格式异常: " + argsInst);
        }
        String version = elems[1];
        String[] paramNames = new String[elems.length - 2];
        System.arraycopy(elems, 2, paramNames, 0, paramNames.length);
        String[] paramValues = new String[paramNames.length];
        for (int i = 0; i < paramNames.length; i++) {
            paramValues[i] = resolveParam(paramNames[i]);
        }
        String[] connectParts = new String[paramValues.length + 1];
        connectParts[0] = version;
        System.arraycopy(paramValues, 0, connectParts, 1, paramValues.length);
        writeInstruction("connect", connectParts);
        log.info("[gateway-server] Guacamole autoConnect 完成: protocol={} target={}:{}",
                connection.protocol(), connection.host(), connection.port());
    }

    /**
     * 解析一条 guacamole 指令为元素数组（去掉 opcode 名）。
     *
     * @param instruction 不含终止符 {@code ;} 的指令字符串
     * @return 元素数组
     */
    private static String[] parseInstructionElements(String instruction) {
        java.util.List<String> elems = new java.util.ArrayList<>();
        int p = 0;
        while (p < instruction.length()) {
            int dot = instruction.indexOf('.', p);
            if (dot < 0) {
                break;
            }
            int len = Integer.parseInt(instruction.substring(p, dot));
            String val = instruction.substring(dot + 1, dot + 1 + len);
            elems.add(val);
            p = dot + 1 + len;
            if (p < instruction.length() && instruction.charAt(p) == ',') {
                p++;
            }
        }
        return elems.toArray(new String[0]);
    }

    /**
     * 根据参数名解析连接参数值（host/port/user/password 等）。
     *
     * @param name 参数名
     * @return 参数值
     */
    private String resolveParam(String name) {
        Connection c = connection;
        switch (name) {
            case "hostname":
                return c.host() == null ? "" : c.host();
            case "port":
                return String.valueOf(c.port() <= 0 ? 22 : c.port());
            case "username":
                return c.user() == null ? "" : c.user();
            case "password":
                return c.password() == null ? "" : c.password();
            case "font-name":
                return "monospace";
            case "font-size":
                return "12";
            case "enable-sftp":
                return "true";
            case "terminal-type":
                return "linux";
            case "scrollback":
                return "10000";
            case "locale":
                return "en_US";
            case "color-scheme":
                return "en_US";
            case "read-only":
                return "false";
            default:
                return "";
        }
    }

    /**
     * 写一条 guacamole 指令（元素用 {@code length.value,} 编码，末尾 {@code ;}）。
     *
     * @param opcode 指令名
     * @param args   参数
     * @throws IOException 写入失败
     */
    private void writeInstruction(String opcode, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        appendElement(sb, opcode);
        for (String arg : args) {
            appendElement(sb, arg);
        }
        sb.setCharAt(sb.length() - 1, ';');
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * 从 guacd 读取完整一行指令（以 {@code ;} 结尾）。
     *
     * @param timeoutMs 超时毫秒
     * @return 指令字符串（不含 {@code ;}），超时返回 null
     * @throws IOException 读取失败
     */
    public String readGuacdInstruction(long timeoutMs) throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        InputStream in = socket.getInputStream();
        socket.setSoTimeout((int) timeoutMs);
        StringBuilder sb = new StringBuilder();
        int prev = -1, cur;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int b;
            try {
                b = in.read();
            } catch (java.net.SocketTimeoutException ex) {
                break;
            }
            if (b == -1) {
                break;
            }
            if (b == ';') {
                break;
            }
            sb.append((char) b);
        }
        socket.setSoTimeout(GUACD_READ_TIMEOUT_MS);
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public void writeToRemote(byte[] bytes) throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        OutputStream out = socket.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    @Override
    public byte[] readFromRemote() throws IOException {
        Objects.requireNonNull(socket, "guacd socket 未连接");
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[65536];
        int n;
        try {
            n = in.read(buf);
        } catch (java.net.SocketTimeoutException ex) {
            // 读超时 ≠ 连接断开：guacd 在收到浏览器 size/connect 之前不会主动推送。
            // 视为暂时无数据，返回空数组让 pump 线程继续轮询。
            return new byte[0];
        }
        if (n <= 0) {
            throw new IOException("guacd 服务器关闭连接");
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
