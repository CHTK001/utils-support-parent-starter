package com.chua.runtime.agent;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.spy.SpyBootstrap;
import java.lang.instrument.Instrumentation;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
* 运行时 智能体 入口 — 通过 agentmain/premain 注入运行时管理能力。
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
    * JUL 日志记录器 — 不依赖 slf4j。
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

    /** 创建 runtime智能体 实例 */
    private RuntimeAgent() {
    }

    /**
    * 智能体 启动入口（运行中注入）。
    *
    * @param args 智能体 参数
    * @param inst Instrumentation 实例
     */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /**
    * 智能体 启动入口（JVM 启动时加载）。
    *
    * @param args 智能体 参数
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
            apm.start();
            started = true;
            LOG.info("Runtime Agent 启动成功");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Runtime Agent 启动异常", e);
        }
    }

    /**
    * 把当前 智能体 JAR 追加到系统类加载器。
    *
    * <p>关键：因为 {@code java.net.Socket} 等 JDK 类由 <b>bootstrap classloader</b> 加载，
    * 它们织入的 {@code INVOKESTATIC com.chua.runtime.agent.Bootstrap.onIntercept} 必须在
    * bootstrap classloader 内可解析。因此 shaded 智能体 JAR 必须同时存在于 系统 和 bootstrap
    * 类路径。这样 Bootstrap/runtimespy/拦截器 接口全部在 bootstrap 内一致，
    * 不会出现 linkage错误。</p>
    *
    * <p>注意：使用前请确保启动命令包含 {@code -Xbootclasspath/a:agent.jar} 优先于
    * {@code -javaagent:agent.jar}，这样 Premain-Class {@code RuntimeAgent} 本身由
    * bootstrap 加载，避免 javaagent 路径将 runtime智能体 装入 app classloader 后再将
    * 同名类塞入 bootstrap 触发 loader constraint violation。</p>
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
            inst.appendToBootstrapClassLoaderSearch(new JarFile(agentPath));
            LOG.info("[RuntimeAgent] 已追加 Agent JAR 到启动类加载器: " + agentPath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "追加 Agent JAR 到启动类加载器失败: " + agentPath, e);
        }
        try {
            Class<?> c = ReflectUtils.forName("com.chua.runtime.agent.Bootstrap",
                    ClassLoader.getSystemClassLoader());
            LOG.info("[RuntimeAgent] Bootstrap 可见 (system): " + c.getName() + " @ " + c.getClassLoader());
        } catch (Throwable e) {
            LOG.warning("[RuntimeAgent] Bootstrap 在系统类加载器不可见: " + e.getMessage());
        }
        try {
            Class<?> c = ReflectUtils.forName("com.chua.runtime.agent.Bootstrap");
            LOG.info("[RuntimeAgent] Bootstrap 可见 (bootstrap): " + c.getName() + " @ " + c.getClassLoader());
        } catch (Throwable e) {
            LOG.warning("[RuntimeAgent] Bootstrap 在启动类加载器不可见: " + e.getMessage());
        }
    }

    /**
    * 通过 protectiondomain 解析 智能体 jar 的绝对路径。
    *
    * @return jar 绝对路径，无法解析返回 空
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
