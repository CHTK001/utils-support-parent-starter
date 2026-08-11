package com.chua.gateway.server;

import com.chua.gateway.server.artifact.GatewayArtifact;
import com.chua.gateway.server.artifact.GuacdBootstrapper;
import com.chua.gateway.server.artifact.GuacdContainerBootstrapper;
import com.chua.gateway.server.config.GatewayProperties;
import com.chua.gateway.server.server.GatewayServerBootstrap;
import com.chua.runtime.starter.RuntimeBoot;
import lombok.extern.slf4j.Slf4j;

/**
 * Gateway Server 主入口（main）。
 *
 * <p>一键启动流程（优先级链）：</p>
 * <ol>
 *   <li>Docker 路径：{@link GuacdContainerBootstrapper#ensureGuacdContainer()} —
 *       通过 Docker API 自动确保 guacd 容器在跑（推荐，零依赖）</li>
 *   <li>本地子进程：{@link GuacdBootstrapper#bootstrapAndStart()} —
 *       local-override / classpath zip / 包管理器 / 系统路径（兜底）</li>
 *   <li>{@link GatewayServerBootstrap#start()} — 启动 HTTP API + 独立 WS 桥接 (:8182)</li>
 * </ol>
 *
 * <p>用户只需执行 {@code run-gateway.bat}（Windows）或 {@code start-gateway.sh}（Linux），
 * gateway 自动确保 guacd 可用。</p>
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
        log.info("[gateway-server] Docker API: {}", GuacdContainerBootstrapper.resolveDockerApiUrl());
        log.info("[gateway-server] ===========================================");

        // 1. RuntimeBoot 安装 GatewayArtifact（仅注册元数据，不下载）
        RuntimeBoot.create()
                .withArtifact(GatewayArtifact.create())
                .install();
        log.info("[gateway-server] RuntimeBoot install 完成");

        // 2. 一键确保 guacd 在跑（Docker 路径优先）
        //    - Docker 可达 → 拉起 guacd 容器（推荐）
        //    - Docker 不可达 → 兜底走 GuacdBootstrapper（子进程）
        //    - 都失败 → 仅警告，gateway 仍启动（SSH / WS 协议仍可用）
        boolean guacdReady = GuacdContainerBootstrapper.ensureGuacdContainer();
        if (!guacdReady) {
            log.info("[gateway-server] Docker 路径未就绪，尝试本地子进程...");
            GuacdBootstrapper.GuacdHandle handle = GuacdBootstrapper.bootstrapAndStart();
            if (handle != null) {
                guacdReady = true;
                log.info("[gateway-server] ✓ guacd 子进程运行中: pid={} port={} source={}",
                        handle.process().pid(), handle.port(), handle.source());
            }
        }
        if (!guacdReady) {
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
