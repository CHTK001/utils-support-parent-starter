package com.chua.remote.support.gateway.core;

import com.chua.remote.support.spi.RemoteGatewayRequest;
import com.chua.remote.support.spi.RemoteGatewayResponse;
import com.chua.remote.support.spi.RemoteGatewaySpi;
import com.chua.remote.support.gateway.core.router.TargetEntry;
import com.chua.remote.support.gateway.core.router.TargetRegistry;
import com.chua.remote.support.gateway.core.session.SessionManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 远程网关 SPI 实现
 * <p>通过 SPI 机制对外提供网关能力查询和连接构建接口，
 * 供外部系统（如监控平台、自动化工具）集成。
 *
 * @author CH
 */
@Slf4j
public class GatewayRemoteSpiImpl implements RemoteGatewaySpi {
    /** 目标注册表 — 查询目标节点信息 */
    private final TargetRegistry targetRegistry;
    /** 会话管理器 — 管理远程会话 */
    private final SessionManager sessionManager;

    public GatewayRemoteSpiImpl(TargetRegistry tr, SessionManager sm) { this.targetRegistry = tr; this.sessionManager = sm; }

    @Override public String getProvider() { return "gateway"; }

    /**
     * 构建
     * @param request 参数
     * @return 构建结果
     */
    @Override
    public RemoteGatewayResponse build(RemoteGatewayRequest request) {
        if (request == null) { return RemoteGatewayResponse.builder().provider("gateway").enabled(false).message("null request").build(); }
        try {
            if (request.getServerId() != null) {
                String targetId = String.valueOf(request.getServerId());
                TargetEntry target = targetRegistry.lookup(targetId);
                if (target == null) { return RemoteGatewayResponse.builder().provider("gateway").enabled(false).message("target not found").build(); }
                return RemoteGatewayResponse.builder().provider("gateway").enabled(true).protocol(request.getProtocol())
                        .gatewayUrl("tcp://" + request.getHost() + ":" + target.getPort()).connectionId(targetId).message("ok").build();
            }
            return RemoteGatewayResponse.builder().provider("gateway").enabled(true).message("ready").build();
        }
 catch (Exception e) {
            log.error("RemoteGatewaySpi error", e);
            return RemoteGatewayResponse.builder().provider("gateway").enabled(false).message(e.getMessage()).build();
        }
    }
}
