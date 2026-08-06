package com.chua.runtime.agent;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.spy.SpyBootstrap;
import lombok.extern.slf4j.Slf4j;

import java.lang.instrument.Instrumentation;
import java.nio.file.Paths;
import java.util.jar.JarFile;

/**
 * 运行时 Agent 入口 — 通过 agentmain 注入运行时管理能力。
 *
 * <p>使用方式：</p>
 * <pre>
 * // 启动时携带
 * java -javaagent:utils-support-runtime-agent.jar=key=value -jar app.jar
 *
 * // 运行中注入（通过 runtime-starter）
 * RuntimeManager.attachToJvm(pid, agentPath, "key=value")
 * </pre>
 *
 * <p>支持的 Agent 参数（逗号分隔 key=value）：</p>
 * <ul>
 *   <li>plugins — 插件目录路径</li>
 *   <li>enabled — 是否启用，默认 true</li>
 *   <li>port — Shell 端口，默认 4567</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RuntimeAgent {

    /**
     * 默认 Shell 端口
     */
    private static final int DEFAULT_PORT = 4567;

    /**
     * 是否已启动
     */
    private static volatile boolean started;

    private RuntimeAgent() {
    }

    /**
     * Agent 启动入口（运行中注入）。
     *
     * @param args Agent 参数
     * @param inst Instrumentation 实例
     */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /**
     * Agent 启动入口（JVM 启动时加载）。
     *
     * @param args Agent 参数
     * @param inst Instrumentation 实例
     */
    public static void premain(String args, Instrumentation inst) {
        if (started) {
            log.warn("Runtime Agent 已启动，忽略重复加载");
            return;
        }
        log.info("Runtime Agent 启动中，参数: {}", args);
        try {
            // 将 Agent JAR 追加到系统类路径，保证 com.chua 类可用
            appendToClasspath(inst);
            // 初始化 Spy 引擎
            boolean ok = SpyBootstrap.init(args, inst);
            if (!ok) {
                log.error("Runtime Agent 初始化失败");
                return;
            }
            // 启动 APM 处理器（4 默认 + 3 新增）
            ApmBootstrap apm = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
            apm.addHandler(new TransmissionHandler());
            apm.addHandler(new DependencyGraphHandler());
            apm.addHandler(new HandleLeakHandler());
            apm.start();
            started = true;
            log.info("Runtime Agent 启动成功");
        } catch (Exception e) {
            log.error("Runtime Agent 启动异常", e);
        }
    }

    /**
     * 将当前 Agent JAR 追加到系统类加载器。
     *
     * @param inst Instrumentation 实例
     */
    private static void appendToClasspath(Instrumentation inst) {
        String agentPath = System.getProperty("java.class.path");
        if (agentPath == null || agentPath.isBlank()) {
            return;
        }
        for (String path : agentPath.split(java.io.File.pathSeparator)) {
            if (path.endsWith(".jar") && path.contains("runtime-agent")) {
                try {
                    inst.appendToSystemClassLoaderSearch(new JarFile(path));
                    log.debug("已追加 Agent JAR 到系统类路径: {}", path);
                } catch (Exception e) {
                    log.warn("追加 Agent JAR 失败: {}", path, e);
                }
            }
        }
    }

    /**
     * 获取默认 Shell 端口。
     *
     * @return 端口
     */
    public static int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 是否已启动。
     *
     * @return 已启动返回 true
     */
    public static boolean isStarted() {
        return started;
    }
}
