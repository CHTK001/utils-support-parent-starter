package com.chua.common.support.network.sip;

import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

/**
 * 服务侧隧道代理，将本机的 TCP 服务暴露为 SIP 隧道服务，供对端访问。
 *
 * <p>实现"内网穿透"的服务端侧：本机服务（如 {@code 127.0.0.1:8080}）无需对外网开放，
 * 只要本代理与 SipServer 建立长连接并注册服务，对端即可通过隧道访问该服务。</p>
 *
 * <p><strong>动态目标中继（通配模式）</strong>：{@code serviceName} 为 {@code "*"} 时进入
 * 全端口中继模式——不绑定固定服务，visitor 请求的服务名即为目标 {@code host:port}，
 * 本代理现场拨号。可选 {@code allowPrefixes} 白名单（对目标主机做前缀匹配）约束可拨号范围；
 * 未配置白名单时放行全部目标（请谨慎在不可信网络使用）。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 固定服务
 * SipClient client = SipClient.tcp("tcp://127.0.0.1:19460").token("xxx");
 * client.service("web").to("127.0.0.1", 8080);
 *
 * // 全端口中继（仅允许内网网段）
 * SipClient relay = SipClient.tcp("tcp://127.0.0.1:19460").token("xxx");
 * new SipTunnelService(relay, "*", "127.0.0.1", 0,
 *         List.of("192.168.", "10.", "127.0.0.1")).start();
 * // visitor 侧: client.tunnel("192.168.200.120:3389").listen(13389);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SipTunnelService {

    /**
     * 底层 SIP 客户端
     */
    private final SipClient client;

    /**
     * 服务名称（"*" 表示动态目标中继模式）
     */
    private final String serviceName;

    /**
     * 本地服务地址（通配模式下忽略）
     */
    private final String localHost;

    /**
     * 本地服务端口（通配模式下忽略）
     */
    private final int localPort;

    /**
     * 通配模式目标白名单（对目标 host 做前缀匹配）；null 或空表示不限制
     */
    private final List<String> allowPrefixes;

    /**
     * 是否已启动
     */
    private volatile boolean started;

    /**
     * 创建服务侧隧道代理。
     *
     * @param client      底层 SIP 客户端
     * @param serviceName 服务名称（"*" 表示动态目标中继）
     * @param localHost   本地服务地址
     * @param localPort   本地服务端口
     */
    public SipTunnelService(SipClient client, String serviceName, String localHost, int localPort) {
        this(client, serviceName, localHost, localPort, null);
    }

    /**
     * 创建服务侧隧道代理（通配模式可指定目标白名单）。
     *
     * @param client        底层 SIP 客户端
     * @param serviceName   服务名称（"*" 表示动态目标中继）
     * @param localHost     本地服务地址
     * @param localPort     本地服务端口
     * @param allowPrefixes 通配模式目标白名单（host 前缀匹配），null/空表示不限制
     */
    public SipTunnelService(SipClient client, String serviceName, String localHost, int localPort,
                            List<String> allowPrefixes) {
        this.client = client;
        this.serviceName = serviceName;
        this.localHost = localHost;
        this.localPort = localPort;
        this.allowPrefixes = allowPrefixes;
    }

    /**
     * 启动服务侧隧道代理：注册服务并监听隧道开启请求。
     *
     * @return 当前代理实例，支持链式调用
     */
    public SipTunnelService start() {
        if (started) {
            return this;
        }
        started = true;
        boolean wildcard = isWildcard();
        client.onTunnelOpen((channelId, name) -> {
            boolean match = wildcard ? allowTarget(name) : serviceName.equals(name);
            if (match) {
                // 异步桥接，避免阻塞信号读取线程
                ThreadUtils.startVirtualThread("sip-service-bridge-" + serviceName, () -> {
                    SipTunnelSession session = client.tunnelSession(channelId);
                    if (session != null) {
                        bridgeToLocal(session, name);
                    }
                });
            } else {
                // 拒绝目标：主动关闭会话，让 visitor 侧快速感知而非挂起超时
                ThreadUtils.startVirtualThread("sip-service-reject-" + serviceName, () -> {
                    SipTunnelSession session = client.tunnelSession(channelId);
                    if (session != null) {
                        log.warn("SIP 隧道目标被拒绝，关闭会话: {}", name);
                        session.close();
                    }
                });
            }
        });
        client.connect().registerTunnel(serviceName);
        if (wildcard) {
            log.info("SIP 动态目标中继已暴露: [{}] 白名单={}", serviceName,
                    allowPrefixes == null || allowPrefixes.isEmpty() ? "不限制" : allowPrefixes);
        } else {
            log.info("SIP 隧道服务已暴露: [{}] -> {}:{}", serviceName, localHost, localPort);
        }
        return this;
    }

    /**
     * 关闭服务侧隧道代理。
     */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        client.close();
    }

    /**
     * 是否通配（动态目标中继）模式。
     *
     * @return true 表示通配模式
     */
    private boolean isWildcard() {
        return SipProtocol.WILDCARD_SERVICE.equals(serviceName);
    }

    /**
     * 通配模式下的目标白名单校验（对目标 host 做前缀匹配）。
     *
     * @param targetName visitor 请求的目标（host:port 或固定服务名）
     * @return true 表示允许拨号
     */
    private boolean allowTarget(String targetName) {
        if (allowPrefixes == null || allowPrefixes.isEmpty()) {
            return true;
        }
        String host = hostPart(targetName);
        for (String prefix : allowPrefixes) {
            if (prefix != null && !prefix.isEmpty() && host.startsWith(prefix)) {
                return true;
            }
        }
        log.warn("SIP 动态目标被白名单拒绝: {}", targetName);
        return false;
    }

    /**
     * 从目标描述中提取 host 部分（host:port → host）。
     *
     * @param targetName 目标描述
     * @return host 部分
     */
    private static String hostPart(String targetName) {
        if (targetName == null) {
            return "";
        }
        int colon = targetName.lastIndexOf(':');
        return colon > 0 ? targetName.substring(0, colon) : targetName;
    }

    /**
     * 将隧道会话桥接到本地服务。
     *
     * @param session    隧道会话
     * @param targetName visitor 请求的服务名/目标（通配模式下为 host:port）
     */
    private void bridgeToLocal(SipTunnelSession session, String targetName) {
        String host = localHost;
        int port = localPort;
        if (isWildcard()) {
            int colon = targetName == null ? -1 : targetName.lastIndexOf(':');
            if (colon <= 0) {
                log.warn("SIP 动态目标格式非法（应为 host:port）: {}", targetName);
                session.close();
                return;
            }
            try {
                host = targetName.substring(0, colon);
                port = Integer.parseInt(targetName.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                log.warn("SIP 动态目标端口非法: {}", targetName);
                session.close();
                return;
            }
        }
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            log.debug("SIP bridgeToLocal: channel={}, connected to {}:{}", session.getChannelId(), host, port);
            session.onBytes(data -> writeSocket(socket, data));
            session.onClose(channelId -> closeQuietly(socket));
            readSocket(socket, session);
        } catch (IOException e) {
            log.error("SIP 隧道连接本地服务失败: {}:{}", host, port, e);
            session.close();
        }
    }

    /**
     * 读取本地服务数据并写入隧道。
     *
     * @param socket  本地服务连接
     * @param session 隧道会话
     */
    private void readSocket(Socket socket, SipTunnelSession session) {
        ThreadUtils.startVirtualThread("sip-tunnel-service-" + serviceName, () -> {
            try (InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (!session.isOpen()) {
                        break;
                    }
                    session.sendBytes(Arrays.copyOf(buffer, read));
                }
            } catch (IOException ignored) {
            } finally {
                session.close();
            }
        });
    }

    /**
     * 将隧道数据写入本地服务。
     *
     * @param socket 本地服务连接
     * @param data   字节数据
     */
    private void writeSocket(Socket socket, byte[] data) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(data);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /**
     * 静默关闭连接。
     *
     * @param socket 连接
     */
    private void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
