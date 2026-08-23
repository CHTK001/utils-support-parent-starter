package com.chua.gateway.server.artifact;

import com.chua.common.support.lang.cmd.RuntimeType;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.gateway.server.config.GatewayProperties;

/**
 * Gateway Server 自身的 RuntimeArtifact 描述。
 *
 * <p>由 {@code RuntimeBoot.install()} 加载，写入启动器注册表。
 * 不下载任何东西 — 仅描述网关服务进程本身，不启动子进程。
 * guacd 等子进程由 {@link GuacdArtifact} 等单独定义。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class GatewayArtifact {

    /**
     * artifact 唯一标识
     */
    private static final String ARTIFACT_ID = "gateway-server";

    /**
     * artifact 名称
     */
    private static final String NAME = "Gateway Server";

    /**
     * 启动超时（毫秒）
     */
    private static final long STARTUP_TIMEOUT_MS = 60_000L;

    /**
     * 私有构造，禁止实例化。
     */
    private GatewayArtifact() {
    }

    /**
     * 构造 Gateway Server 的 RuntimeArtifact 描述符。
     *
     * @return RuntimeArtifact 实例
     */
    public static RuntimeArtifact create() {
        return RuntimeArtifact.builder()
                .id(ARTIFACT_ID)
                .name(NAME)
                .type(RuntimeType.JAR)
                .workDir(new java.io.File(GatewayProperties.artifactDir()).toPath())
                .autoRestart(false)
                .startupTimeoutMs(STARTUP_TIMEOUT_MS)
                .build();
    }
}
