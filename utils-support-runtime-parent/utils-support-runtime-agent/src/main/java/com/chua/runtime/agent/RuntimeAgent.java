package com.chua.runtime.agent;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.spy.SpyBootstrap;
import java.lang.instrument.Instrumentation;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 运行时 Agent 入口 — 通过 agentmain/premain 注入运行时管理能力。
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
public class RuntimeAgent {

    /**
     * JUL Logger — 不依赖 slf4j。
     */
    private static final Logger LOG = Logger.getLogger(RuntimeAgent.class.getName());

    /**
     * 默认 Shell 端口。
     */
    private static final int DEFAULT_PORT = 4567;

    /**
     * 是否已启动。
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
            LOG.log(Level.WARNING, "Runtime Agent 已启动，忽略重复加载");
            return;
        }
        LOG.info("Runtime Agent 启动中，参数: " + args);
        try {
            appendToClasspath(inst);
            boolean ok = SpyBootstrap.init(args, inst);
            if (!ok) {
                LOG.severe("Runtime Agent 初始化失败");
                return;
            }
            ApmBootstrap apm = new ApmBootstrap(Paths.get(System.getProperty("java.io.tmpdir")));
            apm.addHandler(new TransmissionHandler());
            apm.addHandler(new DependencyGraphHandler());
            apm.addHandler(new HandleLeakHandler());
            apm.start();
            started = true;
            LOG.info("Runtime Agent 启动成功");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Runtime Agent 启动异常", e);
        }
    }

    /**
     * 把当前 Agent JAR 追加到系统类加载器。
     *
     * <p>通过 ProtectionDomain 解析 RuntimeAgent 自身所在 jar 路径，调用
     * {@link Instrumentation#appendToSystemClassLoaderSearch}。
     * 这样 shaded jar 内的 RuntimeSpy / ApmBootstrap / Bootstrap 类都被 system classloader 加载，
     * 业务线程（spring-boot LaunchedURLClassLoader.parent = system classloader）可见。</p>
     *
     * <p>注意：不调用 {@code appendToBootstrapClassLoaderSearch} —
     * 因为这会让 shaded 类进入 bootstrap，而 RuntimeAgent 在 app classloader，
     * 跨 loader 的 Plugin/Handler 接口引用会触发 {@code LinkageError loader constraint violation}。</p>
     *
     * @param inst Instrumentation 实例
     */
    private static void appendToClasspath(Instrumentation inst) {
        String agentPath = resolveAgentJarPath();
        LOG.info("[RuntimeAgent] agent JAR 路径: " + agentPath);
        if (agentPath == null) {
            return;
        }
        try {
            inst.appendToSystemClassLoaderSearch(new JarFile(agentPath));
            LOG.info("[RuntimeAgent] 已追加 Agent JAR 到系统类加载器: " + agentPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "追加 Agent JAR 到系统类加载器失败: " + agentPath, e);
        }
        try {
            Class<?> c = Class.forName("com.chua.runtime.agent.Bootstrap", false,
                    ClassLoader.getSystemClassLoader());
            LOG.info("[RuntimeAgent] Bootstrap 可见 (system): " + c.getName() + " @ " + c.getClassLoader());
        } catch (Throwable e) {
            LOG.warning("[RuntimeAgent] Bootstrap 在系统类加载器不可见: " + e.getMessage());
        }
    }

    /**
     * 通过 ProtectionDomain 解析 agent jar 的绝对路径。
     *
     * @return jar 绝对路径，无法解析返回 null
     */
    private static String resolveAgentJarPath() {
        try {
            ProtectionDomain domain = RuntimeAgent.class.getProtectionDomain();
            if (domain != null && domain.getCodeSource() != null && domain.getCodeSource().getLocation() != null) {
                String loc = domain.getCodeSource().getLocation().toString();
                LOG.fine("[RuntimeAgent] ProtectionDomain location: " + loc);
                if (loc.startsWith("file:") && loc.endsWith(".jar")) {
                    return loc.substring("file:".length());
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "解析 Agent JAR 路径失败: " + e.getMessage());
        }
        return null;
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
