package com.chua.gateway.server;

import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.RuntimeType;
import com.chua.gateway.server.artifact.GatewayArtifact;
import com.chua.gateway.server.artifact.GuacdArtifact;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.gateway.server.server.WsBridgeServer;
import com.chua.runtime.core.manager.RuntimeLauncher;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.starter.GuacamoleArtifact;
import com.chua.runtime.starter.RuntimeBoot;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway Server 主入口（main）。
 *
 * <p>启动流程：</p>
 * <ol>
 *   <li>{@link RuntimeBoot#install()} 下载 guacd + Guacamole WAR</li>
 *   <li>启动 guacd 子进程（NATIVE 类型）</li>
 *   <li>通过 {@code RuntimeLauncher(TOMCAT)} SPI 内嵌 Tomcat 部署 Guacamole WAR</li>
 *   <li>{@link GatewayServerBootstrap#start()} 启动 HTTP API + WS 桥接</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayServerApplication {

    private static final String SHUTDOWN_HOOK_NAME = "gateway-server-shutdown";

    private GatewayServerApplication() {
    }

    public static void main(String[] args) {
        log.info("[gateway-server] ===========================================");
        log.info("[gateway-server] Gateway Server 启动中");
        log.info("[gateway-server] HTTP 端口: {}", GatewayProperties.httpPort());
        log.info("[gateway-server] guacd 端口: {}", GatewayProperties.guacdPort());
        log.info("[gateway-server] artifact 目录: {}", GatewayProperties.artifactDir());
        log.info("[gateway-server] ===========================================");

        // 1. RuntimeBoot 下载 guacd + Guacamole WAR
        RuntimeBoot boot = RuntimeBoot.create()
                .withArtifact(GatewayArtifact.create());
        // Linux 上启动 guacd + Guacamole WAR
        var guacdArtifact = GuacdArtifact.createDefault();
        var guacamoleArtifact = GuacamoleArtifact.createWeb();
        boot.withArtifact(guacdArtifact);
        boot.withArtifact(guacamoleArtifact);
        boot.install();
        log.info("[gateway-server] RuntimeBoot install 完成");

        // 2. 启动 guacd 子进程
        RuntimeBoot.create()
                .withArtifact(guacdArtifact)
                .startAsService();
        log.info("[gateway-server] guacd 子进程启动: 端口 {}", GatewayProperties.guacdPort());

        // 3. 通过 RuntimeLauncher(TOMCAT) SPI 启动内嵌 Tomcat 部署 Guacamole WAR
        String warPath = guacamoleArtifact.getExecutable().toString();
        RuntimeArtifact warArtifact = RuntimeArtifact.builder()
                .id("guacamole-web")
                .name("Apache Guacamole Web")
                .type(RuntimeType.TOMCAT)
                .executable(java.nio.file.Paths.get(warPath))
                .args(java.util.Arrays.asList("/guacamole", "8080", "tomcat"))
                .autoRestart(true)
                .build();
        RuntimeLauncher launcher = RuntimeLauncher.find("TOMCAT");
        if (launcher != null) {
            CmdResult result = launcher.start(warArtifact);
            if (result.getExitCode() == 0) {
                log.info("[gateway-server] Guacamole 内嵌容器已启动: http://0.0.0.0:8080/guacamole");
            } else {
                log.warn("[gateway-server] Guacamole 内嵌容器启动失败: {}", result.getStderr());
            }
        } else {
            log.warn("[gateway-server] 未找到 RuntimeLauncher SPI: TOMCAT");
        }

        // 4. 启动 HTTP API
        GatewayServerBootstrap bootstrap = new GatewayServerBootstrap();
        bootstrap.start();
        log.info("[gateway-server] HTTP 服务已监听: http://{}:{}", "0.0.0.0", GatewayProperties.httpPort());

        // 5. 启动 WS 桥接
        var wsBridge = new WsBridgeServer(bootstrap.tunnelRegistry());
        try {
            wsBridge.start();
            log.info("[gateway-server] WS 桥接服务器已启动: port=8092");
        } catch (Exception e) {
            log.warn("[gateway-server] WS 桥接服务器启动失败: {}", e.getMessage());
        }

        // 6. shutdown hook
        var ws = wsBridge;
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    ws.stop();
                    bootstrap.stop();
                    if (launcher != null) launcher.stop(warArtifact);
                }, SHUTDOWN_HOOK_NAME));
    }
}