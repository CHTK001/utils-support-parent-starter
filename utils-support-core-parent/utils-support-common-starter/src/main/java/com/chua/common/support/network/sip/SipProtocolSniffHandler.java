package com.chua.common.support.network.sip;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.tcp.PrefixProtocolSniffHandler;
import com.chua.common.support.network.tcp.ProtocolSniffHandler;
import com.chua.common.support.network.tcp.ProtocolSniffingTcpServer;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * SIP 单端口协议嗅探处理器：识别认证信令与 frp 数据平面连接并交给 SipServer 处理。
 *
 * <p>连接首行前缀识别：</p>
 * <ul>
 *   <li>{@code AUTH|clientId|host|port|signature} — 认证信令连接（sip 认证换 token）</li>
 *   <li>{@code CONNECT|channelId|role|token} — frp 数据平面连接（内网穿透裸字节流桥接）</li>
 * </ul>
 *
 * <p>通过 {@link ProtocolSniffingTcpServer} 注册后，可与 http/socks5 等其他协议
 * 共享同一公网端口，由服务器按头部字节自动分流，实现单端口同时承载
 * "sip 认证 + frp 内网穿透"。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SipProtocolSniffHandler extends PrefixProtocolSniffHandler {

    /**
     * 承载的 SIP 服务器（复用其认证、隧道注册与数据桥接逻辑）
     */
    private final SipServer server;

    /**
     * 创建 SIP 协议嗅探处理器。
     *
     * <p>嵌入嗅探服务器时，{@code SipServer} 仅作为协议处理器复用，
     * 不再需要调用其 {@code start()} 独立监听端口。</p>
     *
     * @param server SIP 服务器实例
     */
    public SipProtocolSniffHandler(SipServer server) {
        super(SipProtocol.PREFIX_AUTH, SipProtocol.PREFIX_CONNECT);
        this.server = server;
    }

    /**
     * 获取协议类型。
     *
     * @return 协议类型
     */
    @Override
    public ProtocolType protocolType() {
        return ProtocolType.TCP;
    }

    /**
     * 将连接交给 SipServer 处理：按首行 AUTH / CONNECT 前缀分流认证信令与数据平面。
     *
     * @param in  输入流（含服务器已回推的头部字节）
     * @param out 输出流
     */
    @Override
    public void handle(InputStream in, OutputStream out) {
        server.handleStream(in, out);
    }
}
