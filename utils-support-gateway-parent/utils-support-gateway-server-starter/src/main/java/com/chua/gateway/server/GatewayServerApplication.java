package com.chua.gateway.server;

import com.chua.gateway.server.artifact.GatewayArtifact;
import com.chua.gateway.server.artifact.GuacdArtifact;
import com.chua.gateway.server.artifact.GuacdBootstrapper;
import com.chua.gateway.server.artifact.LocalOverrideResolver;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.runtime.starter.RuntimeBoot;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway Server 主入口（main）。
 *
 * <p>一键启动流程（依赖 Apache Guacamole 官方 source tarball 自下载 + 自编译）：</p>
 * <ol>
 *   <li>{@link GuacdArtifact} + RuntimeBoot.install() — 通过 {@link LocalOverrideResolver}
 *       把 guacamole-server source tarball 下载到 cache 目录（local-override 优先）</li>
 *   <li>{@link GuacdBootstrapper#bootstrapAndStart()} — 解压、编译、spawn guacd 子进程</li>
 *   <li>{@link GatewayServerBootstrap#start()} — 启动 HTTP API + 独立 WS 桥接 (:8182)</li>
 * </ol>
 *
 * <p>用户只需执行 {@code run-gateway.bat}（Windows）或 {@code start-gateway.sh}（Linux），
 * gateway 自动处理 guacd 缺失场景。</p>
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
     * 私有构造。
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
        log.info("[gateway-server] WS 端口: {}", GatewayProperties.wsPort());
        log.info("[gateway-server] guacd 端口: {}", GatewayProperties.guacdPort());
        log.info("[gateway-server] artifact 目录: {}", GatewayProperties.artifactDir());
        log.info("[gateway-server] local-override: {}", GatewayProperties.localOverrideDir());
        log.info("[gateway-server] ===========================================");

        // 1. RuntimeBoot 安装 GatewayArtifact（仅注册元数据，不下载）
        RuntimeBoot.create()
                .withArtifact(GatewayArtifact.create())
                .withArtifact(GuacdArtifact.createDefault())
                .install();
        log.info("[gateway-server] RuntimeBoot install 完成");

        // 2. 一键引导 guacd（local-override → classpath jar → Linux 包管理器）
        //    失败时仅警告，不阻塞 gateway（SSH / WS 协议仍可用）
        GuacdBootstrapper.GuacdHandle handle = GuacdBootstrapper.bootstrapAndStart();
        if (handle != null) {
            log.info("[gateway-server] ✓ guacd 子进程运行中: pid={} port={} source={}",
                    handle.process().pid(), handle.port(), handle.source());
        } else {
            log.warn("[gateway-server] guacd 未启动 —— RDP/VNC 协议不可用，SSH 仍可用");
        }

        // 3. 启动 HTTP API + WS 桥接
        GatewayServerBootstrap bootstrap = new GatewayServerBootstrap();
        bootstrap.start();
        log.info("[gateway-server] Gateway 已就绪: http://0.0.0.0:{}, ws=0.0.0.0:{}",
                GatewayProperties.httpPort(), bootstrap.wsBindingPort());

        // 4. shutdown hook（包含 guacd 子进程关闭）
        Runtime.getRuntime().addShutdownHook(
                new Thread(bootstrap::stop, SHUTDOWN_HOOK_NAME));
    }
}
