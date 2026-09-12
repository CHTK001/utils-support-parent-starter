package com.chua.runtime.core.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
* 系统服务描述 — 将 runtimeartifact 注册为操作系统级服务时的配置。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class ManagedService {

    /**
    * 服务类型
     */
    private ServiceType serviceType;

    /**
    * 服务名称
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
    * 关联的工件 标识
     */
    private String artifactId;

    /**
    * 可执行文件路径
     */
    private String executable;

    /**
    * 启动参数
     */
    @Builder.Default
    /** 参数 */
    private List<String> args = new ArrayList<>();

    /**
    * 工作目录
     */
    private String workDir;

    /**
    * 环境变量
     */
    private java.util.Map<String, String> env;

    /**
    * 启动类型
     */
    @Builder.Default
    /** Startup类型 */
    private String startupType = "auto";

    /**
    * 运行用户
     */
    private String runAsUser;

    /**
    * 服务依赖
     */
    @Builder.Default
    /** Dependencies */
    private List<String> dependencies = new ArrayList<>();

    /**
    * 是否崩溃后自动重启
     */
    @Builder.Default
    /** Autorestart */
    private boolean autoRestart = true;

    /**
    * 重启间隔
     */
    @Builder.Default
    /** restartsec */
    private int restartSec = 10;

    /**
    * 服务类型枚举
    * @author CH
    * @since 4.0.0
     */
    public enum ServiceType {
        WINDOWS_SERVICE,
        SYSTEMD,
        INIT_D,
        LAUNCHD,
        AUTO
    }
}