package com.chua.kcp.support.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 基于 kcp-基础 的 KCP HTTP 服务器（简化版）。
 *
 * <p>在 KCP 之上提供 HTTP 风格请求-响应，使用 {@code topic=path} 映射 HTTP path，
 * payload 为 JSON 字符串。响应通过 {@code resp/path} 主题回传。</p>
 *
 * <p>注意：这是简化实现，不处理完整 HTTP 语义，仅作 KCP 作为传输层的示例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class KcpHttpServer extends AbstractServer {

    /**
     * KCP 会话标识（conv），两端保持一致
     */
    public static final int KCP_CONV = 0x48455054;

    /**
     * 底层 KCP 服务器实例
     */
    private KcpServer kcpServer;

    /**
     * 创建 kcphttp服务端 实例
     * @param setting setting
     */
    public KcpHttpServer(ServerSetting setting) {
        super(setting);
    }

    @Override
    /**
     * 获取协议
    */
    public String getProtocol() {
        return "kcp";
    }

    @Override
    /**
     * 获取协议类型
    */
    public ProtocolType getProtocolType() {
        return ProtocolType.KCP;
    }

    @Override
    /**
     * 执行开始
    */
    protected void doStart() {
        setting.setProtocol("kcp");
        kcpServer = new KcpServer(setting);
        // 简化：不在 KCP 之上做完整 HTTP 解析，仅作为协议入口占位
        kcpServer.start();
        log.info("KCP HTTP 服务器启动: {}:{}", setting.getHost(), setting.getPort());
    }

    @Override
    /**
     * 执行停止
    */
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
