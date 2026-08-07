package com.chua.gateway.server;

import com.chua.gateway.server.artifact.GatewayArtifact;
import com.chua.gateway.server.artifact.GuacdArtifact;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.runtime.starter.GuacamoleArtifact;
import com.chua.runtime.starter.RuntimeBoot;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway Server 主入口（main）。
 *
 * <p>启动流程：</p>
 * <ol>
 *   <li>{@link RuntimeBoot#install()} 同步安装 artifact
 *       <ul>
 *         <li>{@code GatewayArtifact} — 本服务的描述符（不下载，仅注册）</li>
 *         <li>{@link GuacamoleArtifact#createDefault()} 委托 runtime-starter 的封装版本（按需下载 guacd）</li>
 *       </ul>
 *   </li>
 *   <li>{@code RuntimeBoot.startAsService()} 启动 guacd 子进程（后台）</li>
 *   <li>{@link GatewayServerBootstrap#start()} 启动 HTTP server</li>
 * </ol>
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

    private GatewayServerApplication() {
    }

    /**
     * 程序入口。
     *
     * @param args 命令行参数（暂无作用，全通过 Properties 配置）
     */
    public static void main(String[] args) {
        log.info("===========================================");
        log.info("Gateway Server 启动中");
        log.info("HTTP 端口: {}", GatewayProperties.httpPort());
        log.info("guacd 端口: {}", GatewayProperties.guacdPort());
        log.info("artifact 目录: {}", GatewayProperties.artifactDir());
        log.info("local-override: {}", GatewayProperties.localOverrideDir());
        log.info("===========================================");

        // 1. RuntimeBoot 安装（同步阻塞：完成所有 download/install）
        RuntimeBoot.create()
                .withArtifact(GatewayArtifact.create())
                .withArtifact(GuacdArtifact.createDefault())
                .install();
        log.info("RuntimeBoot install 完成");

        // 2. 启动 guacd 子进程（detached）
        RuntimeBoot.create()
                .withArtifact(GuacdArtifact.createDefault())
                .startAsService();
        log.info("guacd 子进程启动: 端口 {}", GatewayProperties.guacdPort());

        // 3. 启动 HTTP server + WS endpoint
        GatewayServerBootstrap bootstrap = new GatewayServerBootstrap();
        bootstrap.start();
        log.info("HTTP 服务已监听: http://{}:{}", "0.0.0.0", GatewayProperties.httpPort());

        // 4. shutdown hook
        Runtime.getRuntime().addShutdownHook(
                new Thread(bootstrap::stop, SHUTDOWN_HOOK_NAME));
    }
}
