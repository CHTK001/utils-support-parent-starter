package com.chua.runtime.starter;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.core.manager.DefaultRuntimeManager;
import com.chua.runtime.core.manager.RuntimeManager;
import com.chua.runtime.core.model.LogStream;
import com.chua.runtime.core.model.ManagedService;
import com.chua.runtime.core.model.RuntimeArtifact;
import com.chua.runtime.core.model.RuntimeStatus;
import com.chua.runtime.core.service.JavaAgentManager;
import com.chua.runtime.core.service.ServiceManager;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.shell.TelnetServer;
import com.chua.runtime.shell.command.builtin.ApmCommand;
import com.chua.runtime.shell.command.builtin.RuntimeCommand;
import com.chua.runtime.spy.SpyBootstrap;
import lombok.Builder;
import lombok.Data;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 运行时启动器 — 软件管理与 Agent 注入的主入口。
 *
 * <p>支持链式操作：下载 &gt; 安装 main &gt; 注入 agent &gt; 启动服务 &gt; 打开 shell。</p>
 *
 * <p>示例（Guacamole 远程网关）：</p>
 * <pre>
 * RuntimeBoot
 *     .create()
 *     .withArtifact(GuacamoleArtifact.builder().build())
 *     .install()
 *     .attachAgent()
 *     .startShell()
 *     .run();
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RuntimeBoot {

    /**
     * LOG
     */
    private static final Logger LOG = Logger.getLogger(RuntimeBoot.class.getName());
    /**
     * 配置
     */
    private final BootConfig config;

    /**
     * 运行时管理器
     */
    private RuntimeManager manager;

    /**
     * APM 启动器
     */
    private ApmBootstrap apm;

    /**
     * Shell 服务器
     */
    private TelnetServer shell;

    /**
     * 是否已运行
     */
    private boolean running;

    /**
     * 创建 RuntimeBoot 实例
     * @param config config
     */
    private RuntimeBoot(BootConfig config) {
        this.config = config;
        this.manager = new DefaultRuntimeManager();
        this.apm = new ApmBootstrap(config.getPluginDir());
        this.shell = new TelnetServer();
    }

    /**
     * 创建启动器。
     *
     * @return 启动器
     */
    public static RuntimeBoot create() {
        return create(BootConfig.builder().build());
    }

    /**
     * 创建启动器。
     *
     * @param config 配置
     * @return 启动器
     */
    public static RuntimeBoot create(BootConfig config) {
        return new RuntimeBoot(config);
    }

    /**
     * 设置工件。
     *
     * @param artifact 工件
     * @return 自身
     */
    public RuntimeBoot withArtifact(RuntimeArtifact artifact) {
        manager.registerOrReplace(artifact);
        return this;
    }

    /**
     * 设置服务配置。
     *
     * @param service 服务配置
     * @return 自身
     */
    public RuntimeBoot withService(ManagedService service) {
        config.setService(service);
        return this;
    }

    /**
     * 设置 Agent 路径。
     *
     * @param agentPath Agent 路径
     * @return 自身
     */
    public RuntimeBoot withAgent(Path agentPath) {
        config.setAgentPath(agentPath);
        return this;
    }

    /**
     * 设置 Agent 选项。
     *
     * @param options Agent 选项
     * @return 自身
     */
    public RuntimeBoot withAgentOptions(String options) {
        config.setAgentOptions(options);
        return this;
    }

    /**
     * 设置 Shell 端口。
     *
     * @param port 端口
     * @return 自身
     */
    public RuntimeBoot withShellPort(int port) {
        config.setShellPort(port);
        return this;
    }

    /**
     * 注册 shell 自定义命令（APM 查看）。
     *
     * @return 自身
     */
    public RuntimeBoot withApmCommand() {
        shell.register(new ApmCommand(apm));
        return this;
    }

    /**
     * 注册自定义 APM 处理器。
     *
     * @param handler 自定义处理器
     * @return 自身
     */
    public RuntimeBoot withHandler(Plugin handler) {
        apm.addHandler(handler);
        return this;
    }

    /**
     * 链式步骤：下载工件。
     *
     * @return 自身
     */
    public RuntimeBoot install() {
        String id = config.getArtifact().getId();
        if (manager.getArtifact(id) != null) {
            LOG.log(Level.INFO, String.format("工件[%s] 已注册，下载中...", id));
            manager.download(id, new LineCallback() {
                @Override
                /** OnLine */
                public void onLine(String line) {
                    LOG.log(Level.INFO, String.valueOf(line));
                }

                @Override
                /** On记录错误 */
                public void onError(String key, Throwable e) {
                    LOG.log(Level.SEVERE, String.format("下载失败: \" + ke", e));
                }
            }).join();
        } else {
            LOG.log(Level.WARNING, String.format("未找到工件[%s]，跳过下载", id));
        }
        return this;
    }

    /**
     * 链式步骤：注入 Agent 到正在运行的 JVM。
     *
     * @return 自身
     */
    public RuntimeBoot attachAgent() {
        Path agentPath = config.getAgentPath();
        if (agentPath == null || !Files.exists(agentPath)) {
            LOG.log(Level.WARNING, "Agent 路径不存在，跳过注入");
            return this;
        }
        if (config.getPid() == 0) {
            LOG.log(Level.WARNING, "未指定 PID，跳过注入");
            return this;
        }
        manager.attachToJvm(config.getPid(), agentPath, config.getAgentOptions());
        LOG.log(Level.INFO, String.format("Agent 注入完成，PID[%s]", config.getPid()));
        return this;
    }

    /**
     * 链式步骤：启动 shell。
     *
     * @return 自身
     */
    public RuntimeBoot startShell() {
        try {
            shell.start(config.getShellPort());
        } catch (IOException e) {
            LOG.log(Level.WARNING, String.format("Shell 启动失败: %s", e.getMessage()));
        }
        return this;
    }

    /**
     * 链式步骤：启动 APM。
     *
     * @return 自身
     */
    public RuntimeBoot startApm() {
        apm.start();
        return this;
    }

    /**
     * 链式步骤：注册为系统服务并启动。
     *
     * @return 自身
     */
    public RuntimeBoot startAsService() {
        String id = config.getArtifact().getId();
        RuntimeStatus status = manager.status(id);
        if (status == RuntimeStatus.RUNNING) {
            LOG.log(Level.INFO, String.format("工件[%s] 已运行", id));
            return this;
        }
        manager.start(id);
        if (config.getService() != null) {
            manager.installAsService(id, config.getService());
            manager.startService(config.getService().getServiceName());
        }
        return this;
    }

    /**
     * 链式步骤：运行（阻塞）。
     */
    public void run() {
        if (running) {
            LOG.log(Level.WARNING, "已运行");
            return;
        }
        running = true;
        RuntimeStatus status = manager.status(config.getArtifact().getId());
        if (status == RuntimeStatus.STOPPED) {
            LOG.log(Level.INFO, String.format("启动工件: %s", config.getArtifact().getName()));
            manager.start(config.getArtifact().getId());
        }
        LOG.log(Level.INFO, String.format("Runtime 运行中，PID[%s]", manager.getInstance(config.getArtifact().getId())));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 停止所有组件。
     */
    public void shutdown() {
        LOG.log(Level.INFO, "关闭 Runtime...");
        if (shell != null) {
            shell.stop();
        }
        if (apm != null) {
            apm.stop();
        }
        try {
            manager.close();
        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("关闭管理器异常", e));
        }
        running = false;
    }

    /**
     * 获取运行时管理器。
     *
     * @return RuntimeManager
     */
    public RuntimeManager getManager() {
        return manager;
    }

    /**
     * 获取 APM 启动器。
     *
     * @return ApmBootstrap
     */
    public ApmBootstrap getApm() {
        return apm;
    }

    /**
     * 获取 Shell 服务器。
     *
     * @return TelnetServer
     */
    public TelnetServer getShell() {
        return shell;
    }

    /**
     * 启动器配置。
     *
     * @since 4.0.0.42
     */
    @Data
    @Builder
    public static class BootConfig {

        /**
         * 工件
         */
        @Builder.Default
        /** Artifact */
        private RuntimeArtifact artifact = RuntimeArtifact.builder().id("default").build();

        /**
         * 服务配置
         */
        private ManagedService service;

        /**
         * Agent JAR 路径
         */
        private Path agentPath;

        /**
         * Agent 选项
         */
        @Builder.Default
        /** Agentoptions */
        private String agentOptions = "";

        /**
         * 目标 PID
         */
        @Builder.Default
        /** PID */
        private int pid = 0;

        /**
         * Shell 端口
         */
        @Builder.Default
        /** Shell端口 */
        private int shellPort = 4567;

        /**
         * 插件目录
         */
        @Builder.Default
        /** 插件目录 */
        private Path pluginDir = Paths.get("plugins");
    }
}
