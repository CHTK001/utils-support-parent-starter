package com.chua.runtime.core.model;

import com.chua.common.support.lang.cmd.RuntimeType;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可运行工件描述 — 定义一个需要被运行时管理的可运行对象。
 *
 * <p>支持链式创建，通过 {@link RuntimeArtifactBuilder} 构造实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class RuntimeArtifact {

    /**
     * 工件唯一标识
     */
    private String id;

    /**
     * 工件显示名称
     */
    private String name;

    /**
     * 运行时类型
     */
    private RuntimeType type;

    /**
     * 可执行文件路径
     */
    private Path executable;

    /**
     * 工作目录
     */
    private Path workDir;

    /**
     * 下载地址
     */
    private String downloadUrl;

    /**
     * 下载后的文件名
     */
    private String downloadFilename;

    /**
     * 启动参数列表
     */
    @Builder.Default
    /** 参数 */
    private List<String> args = new ArrayList<>();

    /**
    * 环境变量
     */
    @Builder.Default
    private Map<String, String> env = new LinkedHashMap<>(); // env

    /**
     * 启动超时时间（毫秒）
     */
    @Builder.Default
    /** Startup超时MS */
    private long startupTimeoutMs = 30_000;

    /**
    * 健康检查 URL
     */
    private String healthCheckUrl;

    /**
     * 健康检查命令
     */
    private String healthCheckCommand;

    /**
     * 期望的 MD5
     */
    private String expectedMd5;

    /**
     * 是否自动解压下载的文件
     */
    @Builder.Default
    /** Autoextract */
    private boolean autoExtract = false;

    /**
    * 解压目标目录
     */
    private Path extractTo;

    /**
     * 是否自动重启
     */
    @Builder.Default
    /** Autorestart */
    private boolean autoRestart = false;

    /**
    * 最大自动重启次数
     */
    @Builder.Default
    /** 最大值restartattempts */
    private int maxRestartAttempts = 3;
}