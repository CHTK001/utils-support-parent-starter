package com.chua.common.support.network.protocol;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.client.BaseProtocolClient;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.network.protocol.server.impl.TelnetProtocolServer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Telnet协议实现
 * <p>
 * 提供Telnet协议的完整实现，支持增强的服务端功能：
 * 1. 支持基于NIO的高性能服务器
 * 2. 支持多客户端并发连接
 * 3. 支持ServletFilter过滤器链
 * 4. 支持ConfigureObjectContext上下文管理
 * 5. 支持命令行交互
 * 6. 支持会话管理
 * 7. 支持连接状态监控
 * 8. 支持自定义命令处理
 * 9. 支持异步非阻塞I/O
 * 10. 支持连接超时控制
 *
 * @author CH
 * @since 2024/7/8
 */
@Spi("telnet")
public class TelnetProtocol extends AbstractProtocol {

    public TelnetProtocol(ProtocolSetting protocolSetting) {
        super(protocolSetting);
    }

    @Override
    public Class<? extends BaseProtocolClient> getClientType() {
        // Telnet协议主要用于服务端，客户端可以使用标准的Telnet客户端
        // 这里返回null表示不提供专用客户端实现
        return null;
    }

    @Override
    public Class<? extends ProtocolServer> getServerType() {
        return TelnetProtocolServer.class;
    }

    @Override
    protected int getDefaultPort() {
        return 23; // Telnet标准端口
    }

}
