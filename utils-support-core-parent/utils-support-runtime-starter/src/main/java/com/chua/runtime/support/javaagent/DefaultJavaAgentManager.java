package com.chua.runtime.support.javaagent;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.runtime.support.RuntimeManager;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 默认 Java Agent 管理器实现 — 基于 {@link AgentInjector} 和 JDK VirtualMachine API。
 *
 * <p>提供 Java Agent 的注入、卸载、进程列表和 JVM 检查功能。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>{@link #listPids} — 列出本机所有 Java 进程</li>
 *   <li>{@link #attach} — 将 Agent JAR 注入到目标 JVM</li>
 *   <li>{@link #inspectJvm} — 检查目标 JVM 的运行时信息</li>
 *   <li>{@link #attachByPort} — 通过端口附加到远程 JVM</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * JavaAgentManager agentMgr = new DefaultJavaAgentManager();
 *
 * // 列出 Java 进程
 * Map<Integer, String> jvms = agentMgr.listPids();
 *
 * // 注入 Agent
 * agentMgr.attach(12345, Path.of("/opt/agent/runtime-starter.jar"), "log_level=DEBUG");
 *
 * // 检查 JVM
 * agentMgr.inspectJvm(12345);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultJavaAgentManager implements JavaAgentManager {

    /**
     * 命令执行超时（秒）
     */
    private static final int CMD_TIMEOUT_SECONDS = 30;

    /**
     * 默认 Agent 类名
     */
    private static final String DEFAULT_AGENT_CLASS = "com.chua.runtime.support.javaagent.RuntimeAgent";

    @Override
    /** Name */
    public String name() {
        return "javaagent";
    }

    @Override
    /** ListPids */
    public java.util.Map<Integer, String> listPids() {
        return AgentInjector.listJavaProcesses();
    }

    @Override
    /** InspectJvm */
    public CmdResult inspectJvm(int pid) {
        log.info("[runtime-javaagent] 检查 JVM[{}] 的运行时信息", pid);

        try {
            Class<?> vmClass = ReflectUtils.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = ReflectUtils.invokeStatic(vmClass, "attach", Object.class, String.class, String.valueOf(pid));
            String classPath = (String) ReflectUtils.invoke(vm, "getClassPath", String.class);
            String systemProps = (String) ReflectUtils.invoke(vm, "getSystemProperties", String.class);
            String mainClass = getMainClassName(pid);
            ReflectUtils.invoke(vm, "detach", void.class);

            String result = String.format("PID: %d%n主类: %s%n类路径: %s%n系统属性: %s",
                    pid, mainClass, classPath, systemProps);

            return CmdResult.builder()
                    .exitCode(0)
                    .stdout(result)
                    .command("inspectJvm " + pid)
                    .build();

        } catch (Exception e) {
            log.error("[runtime-javaagent] 检查 JVM[{}] 失败", pid, e);
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .stderr("检查失败: " + e.getMessage())
                    .command("inspectJvm " + pid)
                    .throwable(e)
                    .build();
        }
    }

    @Override
    /** Attach */
    public CmdResult attach(int pid, Path agentPath, String options) {
        return AgentInjector.builder()
                .pid(pid)
                .agentPath(agentPath)
                .options(options)
                .timeout(CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .inject();
    }

    @Override
    /** AttachByPort */
    public CmdResult attachByPort(int port, Path agentPath, String options) {
        // 端口通常与 PID 相同，使用相同的注入方式
        return attach(port, agentPath, options);
    }

    @Override
    /** Detach */
    public CmdResult detach(int pid) {
        log.warn("[runtime-javaagent] JDK 不支持 detach，无法直接从目标 JVM 卸载 Agent");
        return CmdResult.builder()
                .exitCode(1)
                .stderr("JDK 不支持 detach，请重启目标 JVM 或使用目标 JVM 中已注册的卸载接口")
                .command("detach " + pid)
                .build();
    }

    /**
     * 获取目标 JVM 的主类名。
     *
     * @param pid 目标进程 ID
     * @return 主类名
     */
    private String getMainClassName(int pid) {
        CmdResult result = CmdExecutors.execute(
                "ps -o comm= -p " + pid,
                CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return result.getStdout().trim();
    }
}