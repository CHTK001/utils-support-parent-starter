package com.chua.runtime.support.javaagent;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
* Java 智能体 链式注入器 — 提供流畅的 API 将 智能体 JAR 注入到目标 JVM。
*
* <p>支持两种注入方式：</p>
* <ul>
*   <li><strong>VirtualMachine.attach()</strong> — JDK 标准机制，适用于同用户进程</li>
*   <li><strong>jattach 命令</strong> — Linux/macOS 专用，可跨用户注入</li>
*   <li><strong>JVM Attach API</strong> — Windows 平台使用 sc.exe 或 tasklist</li>
* </ul>
*
* <h3>链式用法</h3>
* <pre>{@code
* // 将 Agent 注入到 PID 12345 的 JVM
* AgentInjector.builder()
*         .pid(12345)
*         .agentPath(Path.of("/path/to/runtime-starter.jar"))
*         .options("log_level=DEBUG,startup_timeout=60000")
*         .timeout(30, TimeUnit.SECONDS)
*         .callback(line -> System.out.println("[AGENT] " + line))
*         .inject();
*
* // 注入到所有包含特定关键字的 JVM
* AgentInjector.builder()
*         .processNameContains("my-app")
*         .agentPath(Path.of("runtime-starter.jar"))
*         .injectAll();
*
* // 注入到所有 Java 进程
* AgentInjector.builder()
*         .agentPath(Path.of("runtime-starter.jar"))
*         .injectAll();
* }</pre>njectAll();
*
* // 注入到所有 Java 进程
* AgentInjector.builder()
*         .agentPath(Path.of("runtime-starter.jar"))
*         .injectAll();
* }</pre>
*
* <h3>注入后使用</h3>
* <pre>{@code
* // 注入成功后，目标 JVM 中的 RuntimeContextHolder 可用
* // 例如通过 JMX 或 RMI 调用：
* RuntimeContextHolder.register(RuntimeArtifact.builder()
*         .id("target-app")
*         .name("Target Application")
*         .build());
* }</pre>on")
*         .build());
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class AgentInjector {

    /**
    * JDK 虚拟machine 类名
     */
    private static final String VM_CLASS_NAME = "com.sun.tools.attach.VirtualMachine";

    /**
    * jattach 命令路径
     */
    private static final String JATTACH_PATH = "/usr/local/bin/jattach";

    /**
    * 智能体 注入超时（毫秒）
     */
    private static final int DEFAULT_TIMEOUT = 30_000;

    /**
    * 智能体 JAR 路径
     */
    private Path agentPath;

    /**
    * 目标进程 标识
     */
    private Integer pid;

    /**
    * 进程名称匹配（精确匹配）
     */
    private String processName;

    /**
    * 进程名称包含匹配
     */
    private String processNameContains;

    /**
    * 智能体 参数
     */
    private String options;

    /**
    * 注入超时时间
     */
    private long timeoutMs;

    /**
    * 日志回调
     */
    private LineCallback callback;

    /**
    * 注入策略
     */
    private InjectionStrategy strategy;

    /**
    * 私有构造器
     */
    private AgentInjector() {
    }

    /**
    * 创建注入器构建器。
    *
    * @return 注入器构建器
     */
    public static AgentInjectorBuilder builder() {
        return new AgentInjectorBuilder();
    }

    // ==================== 注入执行 ====================

    /**
    * 执行注入。
    *
    * @return 注入结果
     */
    public CmdResult inject() {
        log.info("[runtime-javaagent] 开始注入 Agent 到 PID[{}]...", pid);

        if (pid == null) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("未指定目标进程 PID")
                    .command("inject")
                    .build();
        }

        if (agentPath == null || !Files.exists(agentPath)) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("Agent JAR 不存在: " + agentPath)
                    .command("inject")
                    .build();
        }

        try {
            String agentJarPath = agentPath.toAbsolutePath().toString();

            // 根据策略执行注入
            CmdResult result;
            switch (strategy) {
                case VIRTUAL_MACHINE -> result = injectByVirtualMachine(pid, agentJarPath);
                case JATTACH -> result = injectByJattach(pid, agentJarPath);
                case JPIGEON -> result = injectByJpigeon(pid, agentJarPath);
                case AUTOMATIC -> result = injectByAutomatic(pid, agentJarPath);
                default -> result = injectByVirtualMachine(pid, agentJarPath);
            }

            if (result.isSuccess()) {
                log.info("[runtime-javaagent] Agent 注入成功: PID[{}]", pid);
            } else {
                log.error("[runtime-javaagent] Agent 注入失败: PID[{}], 错误: {}", pid, result.getStderr());
            }

            return result;

        } catch (Exception e) {
            log.error("[runtime-javaagent] Agent 注入异常", e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("注入异常: " + e.getMessage())
                    .command("inject")
                    .throwable(e)
                    .build();
        }
    }

    /**
    * 注入到所有 Java 进程。
    *
    * @return 所有注入结果
     */
    public java.util.List<java.util.Map<String, Object>> injectAll() {
        java.util.List<java.util.Map<String, Object>> results = new ArrayList<>();

        java.util.Map<Integer, String> jvms = listJavaProcesses();
        for (java.util.Map.Entry<Integer, String> entry : jvms.entrySet()) {
            Integer targetPid = entry.getKey();
            String processName = entry.getValue();

            // 进程名称过滤
            if (processNameContains != null && !processName.toLowerCase().contains(processNameContains.toLowerCase())) {
                continue;
            }
            if (processName != null && !processName.equals(processName)) {
                continue;
            }

            // 复制当前注入器并设置目标 PID
            AgentInjector injector = AgentInjector.builder()
                    .pid(targetPid)
                    .agentPath(agentPath)
                    .options(options)
                    .timeout(timeoutMs)
                    .callback(callback)
                    .strategy(strategy)
                    .build();

            CmdResult result = injector.inject();

            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("pid", targetPid);
            map.put("name", processName);
            map.put("success", result.isSuccess());
            map.put("stdout", result.getStdout());
            map.put("stderr", result.getStderr());
            results.add(map);

            if (result.isSuccess() && callback != null) {
                callback.onLine("[AGENT] PID[" + targetPid + "] 注入成功");
            }
        }

        return results;
    }

    // ==================== 注入方式 ====================

    /**
    * 通过 JDK 虚拟machine 注入。
    *
    * @param pid 目标进程 标识
    * @param agentJarPath 智能体 JAR 路径
    * @return 注入结果
     */
    private CmdResult injectByVirtualMachine(int pid, String agentJarPath) {
        try {
            Class<?> vmClass = ReflectUtils.forName(VM_CLASS_NAME);
            Object vm = ReflectUtils.invokeStatic(vmClass, "attach", Object.class, new Class<?>[]{String.class}, String.valueOf(pid));
            String agentClass = getAgentClass(agentJarPath);
            if (agentClass == null) {
                agentClass = "com.chua.runtime.support.javaagent.RuntimeAgent";
            }
            ReflectUtils.invoke(vm, "loadAgent", void.class, String.class, Object.class, agentJarPath, options);
            ReflectUtils.invoke(vm, "detach", void.class);

            if (callback != null) {
                callback.onLine("[AGENT] 注入成功: " + pid);
            }

            return CmdResult.builder()
                    .exitCode(0)
                    .stdout("Agent 注入成功: PID[" + pid + "]")
                    .command("loadAgent " + agentJarPath)
                    .build();

        } catch (Exception e) {
            log.warn("[runtime-javaagent] VirtualMachine 注入失败，尝试其他方式", e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("VirtualMachine 注入失败: " + e.getMessage())
                    .command("loadAgent " + agentJarPath)
                    .throwable(e)
                    .build();
        }
    }

    /**
    * 通过 jattach 命令注入。
    *
    * @param pid 目标进程 标识
    * @param agentJarPath 智能体 JAR 路径
    * @return 注入结果
     */
    private CmdResult injectByJattach(int pid, String agentJarPath) {
        String cmd = String.format("jattach %d load %s %s", pid, agentJarPath,
                options != null ? options : "");
        return CmdExecutors.execute(cmd, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
    * 通过 jpigeon 工具注入。
    *
    * @param pid 目标进程 标识
    * @param agentJarPath 智能体 JAR 路径
    * @return 注入结果
     */
    private CmdResult injectByJpigeon(int pid, String agentJarPath) {
        String cmd = String.format("java -jar jpigeon.jar %d load %s %s", pid, agentJarPath,
                options != null ? options : "");
        return CmdExecutors.execute(cmd, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
    * 自动选择注入方式。
    *
    * @param pid 目标进程 标识
    * @param agentJarPath 智能体 JAR 路径
    * @return 注入结果
     */
    private CmdResult injectByAutomatic(int pid, String agentJarPath) {
 // 尝试 虚拟machine
        CmdResult result = injectByVirtualMachine(pid, agentJarPath);
        if (result.isSuccess()) {
            return result;
        }

        // 尝试 jattach
        if (Files.exists(Path.of(JATTACH_PATH))) {
            return injectByJattach(pid, agentJarPath);
        }

        return result;
    }

    // ==================== 工具方法 ====================

    /**
    * 列出所有 Java 进程。
    *
    * @return PID 到进程描述的映射
     */
    public static java.util.Map<Integer, String> listJavaProcesses() {
        CmdResult result = CmdExecutors.execute(
                "ps -eo pid,comm,args | grep -E '[j]ava|sun.tools.launcher' | grep -v grep",
                10, TimeUnit.SECONDS);

        java.util.Map<Integer, String> jvms = new java.util.LinkedHashMap<>();

        if (result.isSuccess()) {
            String[] lines = result.getStdout().split("\n");
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        int pid = Integer.parseInt(parts[0]);
                        String desc = line.trim();
                        jvms.put(pid, desc);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        return jvms;
    }

    /**
    * 从 JAR Manifest 中获取 智能体-类。
    *
    * @param agentJarPath 智能体 JAR 路径
    * @return Agent 类名，未找到返回 空
     */
    private String getAgentClass(String agentJarPath) {
        try {
            java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJarPath);
            String agentClass = jar.getManifest().getMainAttributes().getValue("Agent-Class");
            jar.close();
            return agentClass;
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== 构建器 ====================

    /**
    * 注入策略枚举。
    * @author CH
    * @since 4.0.0
     */
    public enum InjectionStrategy {
        /**
        * 自动选择
         */
        AUTOMATIC,

        /**
        * JDK 虚拟machine
         */
        VIRTUAL_MACHINE,

        /**
        * jattach 命令
         */
        JATTACH,

        /**
        * jpigeon 工具
         */
        JPIGEON
    }

    /**
    * 智能体injector 构建器 — 链式 API。
    *
    * @since 4.0.0.42
    * @author CH
     */
    public static class AgentInjectorBuilder {

        /**
        * 智能体 JAR 路径
         */
        private Path agentPath;

        /**
        * 目标进程 标识
         */
        private Integer pid;

        /**
        * 进程名称匹配（精确匹配）
         */
        private String processName;

        /**
        * 进程名称包含匹配
         */
        private String processNameContains;

        /**
        * 智能体 参数
         */
        private String options;

        /**
        * 注入超时时间
         */
        private long timeoutMs = DEFAULT_TIMEOUT;

        /**
        * 日志回调
         */
        private LineCallback callback;

        /**
        * 注入策略
         */
        private InjectionStrategy strategy = InjectionStrategy.AUTOMATIC;

        /**
        * 指定 智能体 JAR 路径。
        *
        * @param agentPath 智能体 JAR 路径
        * @return 构建器自身
         */
        public AgentInjectorBuilder agentPath(Path agentPath) {
            this.agentPath = agentPath;
            return this;
        }

        /**
        * 指定 智能体 JAR 路径（字符串形式）。
        *
        * @param agentPath 智能体 JAR 路径
        * @return 构建器自身
         */
        public AgentInjectorBuilder agentPath(String agentPath) {
            this.agentPath = Paths.get(agentPath);
            return this;
        }

        /**
        * 指定目标进程 标识。
        *
        * @param pid 进程 标识
        * @return 构建器自身
         */
        public AgentInjectorBuilder pid(int pid) {
            this.pid = pid;
            return this;
        }

        /**
        * 指定进程名称匹配（精确匹配）。
        *
        * @param processName 进程名称
        * @return 构建器自身
         */
        public AgentInjectorBuilder processName(String processName) {
            this.processName = processName;
            return this;
        }

        /**
        * 指定进程名称包含匹配。
        *
        * @param processNameContains 进程名称包含字符串
        * @return 构建器自身
         */
        public AgentInjectorBuilder processNameContains(String processNameContains) {
            this.processNameContains = processNameContains;
            return this;
        }

        /**
        * 指定 智能体 参数。
        *
        * @param options 智能体 参数（键=值 格式，多参数用逗号分隔）
        * @return 构建器自身
         */
        public AgentInjectorBuilder options(String options) {
            this.options = options;
            return this;
        }

        /**
        * 指定注入超时时间（毫秒）。
        *
        * @param timeoutMs 超时值（毫秒）
        * @return 构建器自身
         */
        public AgentInjectorBuilder timeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
        * 指定注入超时时间。
        *
        * @param timeout 超时值
        * @param unit 时间单位
        * @return 构建器自身
         */
        public AgentInjectorBuilder timeout(long timeout, TimeUnit unit) {
            this.timeoutMs = unit.toMillis(timeout);
            return this;
        }

        /**
        * 指定日志回调。
        *
        * @param callback 日志行回调
        * @return 构建器自身
         */
        public AgentInjectorBuilder callback(LineCallback callback) {
            this.callback = callback;
            return this;
        }

        /**
        * 指定注入策略。
        *
        * @param strategy 注入策略
        * @return 构建器自身
         */
        public AgentInjectorBuilder strategy(InjectionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
        * 构建注入器实例。
        *
        * @return AgentInjector 实例
         */
        public AgentInjector build() {
            AgentInjector injector = new AgentInjector();
            injector.agentPath = agentPath;
            injector.pid = pid;
            injector.processName = processName;
            injector.processNameContains = processNameContains;
            injector.options = options;
            injector.timeoutMs = timeoutMs;
            injector.callback = callback;
            injector.strategy = strategy;
            return injector;
        }

        /**
        * 构建并直接执行注入。
        *
        * @return 注入结果
         */
        public CmdResult inject() {
            return build().inject();
        }

        /**
        * 构建并直接执行批量注入。
        *
        * @return 所有注入结果
         */
        public java.util.List<java.util.Map<String, Object>> injectAll() {
            return build().injectAll();
        }
    }
}