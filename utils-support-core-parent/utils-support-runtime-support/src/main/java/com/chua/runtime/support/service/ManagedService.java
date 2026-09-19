package com.chua.runtime.support.service;

import com.chua.runtime.support.model.RuntimeArtifact;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统服务描述 — 将 {@link RuntimeArtifact} 注册为操作系统级服务时的配置。
 *
 * <p>支持 Windows Service、Linux systemd、Linux init.d 等平台的服务注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManagedService {

    /**
     * 服务类型
     */
    private ServiceType serviceType;

    /**
     * 服务名称（系统级唯一标识）
     */
    private String serviceName;

    /**
     * 服务显示名
     */
    private String displayName;

    /**
     * 服务描述
     */
    private String description;

    /**
     * 关联的运行时工件 标识
     */
    private String artifactId;

    /**
     * 可执行文件路径
     */
    private String executable;

    /**
     * 启动参数
     */
    private List<String> args;

    /**
     * 工作目录
     */
    private String workDir;

    /**
     * 环境变量
     */
    private Map<String, String> env;

    /**
     * 启动类型（auto / manual / 已禁用）
     */
    @Builder.Default
    /** Startup类型 */
    private String startupType = "auto";

    /**
     * 运行用户（systemd 用户= 或 窗口 服务登录账户）
     */
    private String runAsUser;

    /**
     * 服务依赖（其他服务名，如 "network.Target"）
     */
    @Builder.Default
    /** Dependencies */
    private List<String> dependencies = new ArrayList<>();

    /**
     * 是否在崩溃后自动重启
     */
    @Builder.Default
    /** Autorestart */
    private boolean autoRestart = true;

    /**
     * 重启间隔（秒）
     */
    @Builder.Default
    /** restartsec */
    private int restartSec = 10;

    /**
     * 服务类型枚举。
     * @author CH
     * @since 4.0.0
     */
    public enum ServiceType {
        /**
         * 窗口 服务
         */
        WINDOWS_SERVICE,

        /**
         * Linux systemd 服务
         */
        SYSTEMD,

        /**
         * Linux 初始化.d 服务
         */
        INIT_D,

        /**
         * macOS launchd 服务
         */
        LAUNCHD,

        /**
         * 自动检测
         */
        AUTO
    }
}