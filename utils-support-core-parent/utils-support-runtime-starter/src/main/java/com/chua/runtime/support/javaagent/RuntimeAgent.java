package com.chua.runtime.support.javaagent;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.runtime.support.RuntimeManager;
import com.chua.runtime.support.DefaultRuntimeManager;
import com.chua.runtime.support.RuntimeInstance;
import com.chua.runtime.support.model.RuntimeArtifact;
import com.chua.runtime.support.model.RuntimeStatus;
import com.chua.runtime.support.service.ManagedService;
import com.chua.runtime.support.service.ServiceManager;
import lombok.extern.slf4j.Slf4j;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 运行时 Agent 入口 — 可作为 {@code -javaagent} 附加到 JVM，
 * 或在运行时通过 {@code VirtualMachine.attach()} 注入。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li><strong>premain</strong> — 启动时通过 {@code -javaagent} 加载，在 main 方法前初始化运行时管理器</li>
 *   <li><strong>agentmain</strong> — 运行时注入后执行，提供进程管理和监控能力</li>
 * </ul>
 *
 * <h3>启动方式</h3>
 * <pre>
 * # 方式 1：启动时附加
 * java -javaagent:utils-support-runtime-starter.jar=com.chua.runtime.support.javaagent.RuntimeAgent \
 *      -jar my-app.jar
 *
 * # 方式 2：运行时注入（使用 jattach）
 * jattach <target_pid> load utils-support-runtime-starter.jar com.chua.runtime.support.javaagent.RuntimeAgent
 *
 * # 方式 3：直接调用 RuntimeManager
 * RuntimeManager manager = new DefaultRuntimeManager();
 * </pre>
 *
 * <h3>Agent 参数</h3>
 * <p>通过 {@code -javaagent:jar=参数} 或 {@code VirtualMachine.loadAgent(path, 参数)} 传递：</p>
 * <pre>
 * -javaagent:runtime-starter.jar=log_level=DEBUG,startup_timeout=60000
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RuntimeAgent {

    /**
     * 日志级别参数
     */
    private static final String OPT_LOG_LEVEL = "log_level";

    /**
     * 启动超时参数
     */
    private static final String OPT_STARTUP_TIMEOUT = "startup_timeout";

    /**
     * 健康检查间隔（秒）
     */
    private static final String OPT_HEALTH_CHECK_INTERVAL = "health_check_interval";

    /**
     * 启动超时（毫秒）
     */
    private static final int DEFAULT_STARTUP_TIMEOUT = 30_000;

    /**
     * 健康检查间隔（秒）
     */
    private static final int DEFAULT_HEALTH_CHECK_INTERVAL = 30;

    // ==================== Java Agent 入口 ====================

    /**
     * JVM 启动时通过 -javaagent 加载的入口方法。
     *
     * <p>在 main 方法执行前被调用，用于在应用启动时初始化运行时管理器。</p>
     *
     * @param agentArgs   Agent 参数
     * @param inst        Instrumentation 实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("[runtime-javaagent] RuntimeAgent premain 加载，参数: {}", agentArgs);
        try {
            RuntimeManager manager = createRuntimeManager(agentArgs);
            // 注册到 ThreadLocal 供应用随时获取
            RuntimeContextHolder.setManager(manager);
            log.info("[runtime-javaagent] RuntimeAgent premain 初始化完成");
        } catch (Exception e) {
            log.error("[runtime-javaagent] RuntimeAgent premain 初始化失败", e);
        }
    }

    /**
     * 运行时通过 VirtualMachine.attach() 注入的入口方法。
     *
     * <p>在目标 JVM 运行时被调用，用于动态附加运行时管理器到已有进程。</p>
     *
     * @param agentArgs   Agent 参数
     * @param inst        Instrumentation 实例
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        log.info("[runtime-javaagent] RuntimeAgent agentmain 注入，参数: {}", agentArgs);
        try {
            RuntimeManager manager = createRuntimeManager(agentArgs);
            RuntimeContextHolder.setManager(manager);
            log.info("[runtime-javaagent] RuntimeAgent agentmain 注入完成");

            // 尝试执行健康检查
            if (inst != null) {
                // 记录已加载的类信息
                String loadedClassesCount = inst.getAllLoadedClasses().length > 0
                        ? inst.getAllLoadedClasses().length + " classes"
                        : "no classes";
                log.info("[runtime-javaagent] 注入的 JVM 已加载类数: {}", loadedClassesCount);
            }
        } catch (Exception e) {
            log.error("[runtime-javaagent] RuntimeAgent agentmain 注入失败", e);
        }
    }

    // ==================== 运行时管理器创建 ====================

    /**
     * 根据参数创建 RuntimeManager 实例。
     *
     * @param agentArgs Agent 参数
     * @return RuntimeManager 实例
     */
    private static RuntimeManager createRuntimeManager(String agentArgs) {
        AgentParams params = parseAgentArgs(agentArgs);

        RuntimeManager manager = new DefaultRuntimeManager();
        log.info("[runtime-javaagent] RuntimeManager 创建完成，启动超时: {}ms，健康检查间隔: {}s",
                params.startupTimeoutMs, params.healthCheckIntervalSec);
        return manager;
    }

    // ==================== 参数解析 ====================

    /**
     * 解析 Agent 参数。
     *
     * @param agentArgs Agent 参数字符串
     * @return 解析后的参数
     */
    private static AgentParams parseAgentArgs(String agentArgs) {
        if (agentArgs == null || agentArgs.isBlank()) {
            return new AgentParams(DEFAULT_STARTUP_TIMEOUT, DEFAULT_HEALTH_CHECK_INTERVAL);
        }

        int startupTimeout = DEFAULT_STARTUP_TIMEOUT;
        int healthCheckInterval = DEFAULT_HEALTH_CHECK_INTERVAL;

        for (String pair : agentArgs.split(",")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                switch (kv[0].trim().toLowerCase()) {
                    case OPT_LOG_LEVEL -> {
                        // 日志级别暂不处理，留给 SLF4J 配置
                        log.info("[runtime-javaagent] 设置日志级别: {}", kv[1]);
                    }
                    case OPT_STARTUP_TIMEOUT -> {
                        startupTimeout = Integer.parseInt(kv[1].trim());
                    }
                    case OPT_HEALTH_CHECK_INTERVAL -> {
                        healthCheckInterval = Integer.parseInt(kv[1].trim());
                    }
                    default -> log.warn("[runtime-javaagent] 未知参数: {}", kv[0]);
                }
            }
        }

        return new AgentParams(startupTimeout, healthCheckInterval);
    }

    // ==================== 内部类 ====================

    /**
     * Agent 参数容器。
     *
     * @param startupTimeoutMs      启动超时（毫秒）
     * @param healthCheckIntervalSec 健康检查间隔（秒）
     * @author CH
     * @since 4.0.0.42
     */
    private record AgentParams(
            int startupTimeoutMs,
            int healthCheckIntervalSec
    ) {
    }
}