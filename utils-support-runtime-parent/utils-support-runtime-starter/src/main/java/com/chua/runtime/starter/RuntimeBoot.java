package com.chua.runtime.starter;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.reflection.ReflectUtils;
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
     * 日志
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
     * 创建 runtimeboot 实例
     * @param config 配置
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
     * 注册 Shell 自定义命令（APM 查看）。
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
                /** on线 */
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
     * 链式步骤：注入 Agent 到<b>当前 JVM 进程自身</b>（自 attach）。
     *
     * <p>用于"纯 Maven 依赖、零 JVM 启动参数"的自动注入场景。
     * 内部通过 {@code com.sun.tools.attach.VirtualMachine.attach(currentPid)}
     * 把 agent jar 加载进当前 JVM，触发 {@code RuntimeAgent.agentmain}，
     * 进而启动 SpyBootstrap（字节码引擎）+ ApmBootstrap（APM 处理器）。</p>
     *
     * <p>若 {@code BootConfig.agentPath} 未显式设置，则自动定位 agent jar：
     * 优先扫 classpath 上含 {@code com/chua/runtime/agent/RuntimeAgent.class} 的 jar，
     * 找不到时回退扫 Spring Boot fat jar 的 {@code BOOT-INF/lib/*.jar}
     * （适用于 {@code java -jar} 运行的嵌套加载场景）。</p>
     *
     * @return 自身
     */
    public RuntimeBoot attachSelf() {
        // 1. 定位 agent jar
        Path agentPath = config.getAgentPath();
        if (agentPath == null) {
            agentPath = resolveSelfAgentPath();
            if (agentPath == null) {
                LOG.log(Level.WARNING, "无法定位 Agent JAR，跳过自注入。"
                        + "请在启动命令中显式 withAgent(Path) 指定，或将 agent jar 加入 classpath。");
                return this;
            }
            config.setAgentPath(agentPath);
        }
        if (!Files.exists(agentPath)) {
            LOG.log(Level.WARNING, "Agent 路径不存在: " + agentPath + "，跳过自注入");
            return this;
        }
        // 2. 取当前进程 PID
        int pid = Math.toIntExact(ProcessHandle.current().pid());
        // 3. attach 到自身
        LOG.log(Level.INFO, String.format("自注入 Agent 到当前 JVM，PID[%s]，Agent[%s]",
                pid, agentPath.toAbsolutePath()));
        CmdResult result = manager.attachToJvm(pid, agentPath, config.getAgentOptions());
        if (result.isSuccess()) {
            LOG.log(Level.INFO, String.format("自注入成功，PID[%s]", pid));
        } else {
            LOG.log(Level.SEVERE, String.format("自注入失败: %s", result.getStderr()));
        }
        return this;
    }

    /**
     * 定位 agent jar（两级探测）。
     *
     * <p>方案 A：枚举 {@code java.class.path}，找含
     * {@code com/chua/runtime/agent/RuntimeAgent.class} 的 jar（裸 jar / classpath 场景）；
     * 若外层是 Spring Boot fat jar，扫 {@code BOOT-INF/lib/} 下 agent jar 并解压到临时目录。</p>
     *
     * <p>方案 B（回退）：通过 classloader 找 RuntimeAgent 的代码源。</p>
     *
     * @return agent jar 物理路径；定位不到返回 {@code null}
     */
    private static Path resolveSelfAgentPath() {
        // 方案 A：扫 classpath jar
        try {
            java.util.StringTokenizer st =
                    new java.util.StringTokenizer(System.getProperty("java.class.path"), java.io.File.pathSeparator);
            while (st.hasMoreTokens()) {
                String entry = st.nextToken();
                Path candidate = Path.of(entry);
                if (!Files.isRegularFile(candidate) || !candidate.toString().endsWith(".jar")) {
                    continue;
                }
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(candidate.toFile())) {
                    if (jar.getEntry("com/chua/runtime/agent/RuntimeAgent.class") != null) {
                        LOG.log(Level.INFO, "自注入 Agent JAR 定位 (class-scan): " + candidate);
                        return candidate;
                    }
                    // 外层是 Spring Boot fat jar：直接遍历 BOOT-INF/lib/ 下 agent jar
                    Path extracted = extractAgentFromFatJar(candidate);
                    if (extracted != null) {
                        return extracted;
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "自注入定位 Agent JAR (class-scan) 异常", e);
        }
        // 方案 B（回退）：通过 classloader 找 RuntimeAgent 的代码源
        try {
            Class<?> agentClass = ReflectUtils.forName("com.chua.runtime.agent.RuntimeAgent");
            if (agentClass == null) {
                return null;
            }
            java.net.URL loc = agentClass.getProtectionDomain()
                    .getCodeSource().getLocation();
            Path p = Path.of(loc.toURI());
            if (Files.isRegularFile(p)) {
                LOG.log(Level.INFO, "自注入 Agent JAR 定位 (ProtectionDomain): " + p);
                return p;
            }
        } catch (Exception ignored) {
            // classpath 上没有 agent jar
        }
        return null;
    }

    /**
     * 从 Spring Boot fat jar 的 {@code BOOT-INF/lib/} 下找 agent jar 并解压到临时目录。
     *
     * <p>attach 机制（{@code VirtualMachine.loadAgent}）要求物理文件路径，
     * 不能直接喂 fat jar 内的嵌套 jar，故解压到 {@code java.io.tmpdir} 下。</p>
     *
     * @param fatJar 外层 fat jar
     * @return 解压后的 agent jar 物理路径；找不到返回 {@code null}
     */
    private static Path extractAgentFromFatJar(Path fatJar) {
        try {
            java.util.jar.JarFile outer = new java.util.jar.JarFile(fatJar.toFile());
            java.util.Enumeration<java.util.jar.JarEntry> entries = outer.entries();
            java.util.Optional<java.util.jar.JarEntry> target = java.util.Optional.empty();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("BOOT-INF/lib/")
                        && name.contains("utils-support-runtime-agent")
                        && name.endsWith(".jar")) {
                    target = java.util.Optional.of(e);
                    break;
                }
            }
            if (target.isEmpty()) {
                LOG.log(Level.WARNING, "fat jar 内未找到 agent jar: " + fatJar);
                return null;
            }
            LOG.log(Level.INFO, "自注入 Agent JAR 候选 (fat-jar-extract): "
                    + target.get().getName() + " in " + fatJar);
            java.io.File dest = java.nio.file.Files.createTempFile("runtime-agent-", ".jar").toFile();
            try (java.io.InputStream is = outer.getInputStream(target.get());
                 java.io.OutputStream os = java.nio.file.Files.newOutputStream(dest.toPath())) {
                is.transferTo(os);
            }
            LOG.log(Level.INFO, "自注入 Agent JAR 定位 (fat-jar-extract): " + dest.getAbsolutePath());
            return dest.toPath();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "从 fat jar 提取 agent jar 失败: " + fatJar, e);
            return null;
        }
    }

    /**
     * 链式步骤：启动 Shell。
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
     * @author CH
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
