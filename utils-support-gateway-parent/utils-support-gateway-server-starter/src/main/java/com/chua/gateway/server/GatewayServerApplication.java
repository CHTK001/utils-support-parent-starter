package com.chua.gateway.server;

import com.chua.gateway.server.artifact.GatewayArtifact;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.runtime.starter.RuntimeBoot;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway Server 主入口（main）。
 *
 * <p>启动流程：</p>
 * <ol>
 *   <li>{@link RuntimeBoot#install()} 安装 GatewayArtifact（仅注册，不下载）</li>
 *   <li>{@link GatewayServerBootstrap#start()} 启动 HTTP server（含 WS 端点）</li>
 * </ol>
 *
 * <p>控制端在左侧 {@code ConnectionForm} 输入 key 或 custom (protocol/host/port/user/pass)，
 * 后端通过 {@code /api/connections/authenticate} 创建 Tunnel 并返回 wsUrl，
 * 右侧 {@code VncViewer/SshViewer/RdpViewer} 通过 wsUrl 连接到后端 WS 端点
 * 透传 noVNC/xterm.js/guacamole-common-js 数据流。</p>
 *
 * <p>关闭流程由 JVM shutdown hook 驱动。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayServerApplication {

    /**
     * JVM shutdown hook 名称
     */
    private static final String SHUTDOWN_HOOK_NAME = "gateway-server-shutdown";

    /**
     * 私有构造，禁止实例化。
     */
    private GatewayServerApplication() {
    }

    /**
     * 程序入口。
     *
     * @param args 命令行参数（暂无作用，全通过 Properties 配置）
     */
    public static void main(String[] args) {
        log.info("[gateway-server] ===========================================");
        log.info("[gateway-server] Gateway Server 启动中");
        log.info("[gateway-server] HTTP 端口: {}", GatewayProperties.httpPort());
        log.info("[gateway-server] artifact 目录: {}", GatewayProperties.artifactDir());
        log.info("[gateway-server] local-override: {}", GatewayProperties.localOverrideDir());
        log.info("[gateway-server] ===========================================");

        // 1. RuntimeBoot 安装 GatewayArtifact（仅注册，不下载）
        RuntimeBoot.create()
                .withArtifact(GatewayArtifact.create())
                .install();
        log.info("[gateway-server] RuntimeBoot install 完成");

        // 2. 启动 HTTP server（含 WS 端点）
        GatewayServerBootstrap bootstrap = new GatewayServerBootstrap();
        bootstrap.start();
        log.info("[gateway-server] HTTP 服务已监听: http://{}:{}", "0.0.0.0", GatewayProperties.httpPort());

        // 3. shutdown hook
        Runtime.getRuntime().addShutdownHook(
                new Thread(bootstrap::stop, SHUTDOWN_HOOK_NAME));
    }
}