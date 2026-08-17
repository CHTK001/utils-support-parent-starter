package com.chua.kcp.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 基于 kcp-base 的 KCP HTTP 服务器（简化版）。
 *
 * <p>在 KCP 之上提供 HTTP 风格请求-响应，使用 {@code topic=path} 映射 HTTP path，
 * payload 为 JSON 字符串。响应通过 {@code resp/path} 主题回传。</p>
 *
 * <p>注意：这是简化实现，不处理完整 HTTP 语义，仅作 KCP 作为传输层的示例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KcpHttpServer extends AbstractServer {

    private static final Logger log = LoggerFactory.getLogger(KcpHttpServer.class);

    /**
     * KCP 会话标识（conv），两端保持一致
     */
    public static final int KCP_CONV = 0x48455054;

    private KcpServer kcpServer;

    public KcpHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    public String getProtocol() {
        return "kcp";
    }

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.KCP;
    }

    @Override
    protected void doStart() {
        setting.setProtocol("kcp");
        kcpServer = new KcpServer(setting);
        // 简化：不在 KCP 之上做完整 HTTP 解析，仅作为协议入口占位
        kcpServer.start();
        log.info("KCP HTTP 服务器启动: {}:{}", setting.getHost(), setting.getPort());
    }

    @Override
    protected void doStop() {
        if (kcpServer != null) {
            try {
                kcpServer.stop();
            } catch (Exception e) {
                log.warn("KCP HTTP 服务器停止异常: {}", e.getMessage());
            }
        }
        log.info("KCP HTTP 服务器停止");
    }
}
