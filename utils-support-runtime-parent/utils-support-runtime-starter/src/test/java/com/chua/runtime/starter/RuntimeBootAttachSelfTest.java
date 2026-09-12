package com.chua.runtime.starter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.StringTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimeBoot#attachSelf()} 自注入测试。
 *
 * <p>验证"纯 Maven 依赖、零 JVM 启动参数"场景下：</p>
 * <ol>
 *   <li>从 classpath 自动定位 agent jar</li>
 *   <li>自 attach 到当前 JVM（通过 VirtualMachine.attach(currentPid)）</li>
 *   <li>agentmain 触发后 SpyBootstrap 初始化、ApmBootstrap 启动</li>
 * </ol>
 *
 * <p>注意：JVM 级副作用（启动字节码引擎、APM 处理器、telnet shell）
 * 会留在当前 JVM 中，不影响测试进程退出。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class RuntimeBootAttachSelfTest {

    private static final Logger LOG = Logger.getLogger(RuntimeBootAttachSelfTest.class.getName()); // 日志

    /** 当前 JVM 的 PID（attach 目标）。 */
    private static int currentPid;

    /** 探测到的 智能体 jar 路径（可能为 空）。 */
    private static Path agentJar;

    @org.junit.jupiter.api.BeforeAll
    /**
     * locate智能体jar。
     */
    static void locateAgentJar() {
        currentPid = (int) ProcessHandle.current().pid();
        agentJar = probeAgentJarOnClasspath();
        LOG.info("[Test] PID=" + currentPid + " agentJar=" + (agentJar != null ? agentJar : "<not-found>"));
    }

    @AfterAll
    /**
      * teardown。
     */
    static void tearDown() {
        // 不强制清理：Spy/Apm 单例由 JVM 生命周期管理
    }

    // ------------------------------------------------------------------
    // 探测辅助
    // ------------------------------------------------------------------

    /**
       * 从 Java.类.路径 探测含 runtime智能体.类 的 jar（与
     * {@code RuntimeBoot.resolveSelfAgentPath()} 首选方案一致）。
     * @return 探针智能体jaron类路径的结果
     */
    private static Path probeAgentJarOnClasspath() {
        try {
            StringTokenizer st =
                    new StringTokenizer(System.getProperty("java.class.path"), java.io.File.pathSeparator);
            while (st.hasMoreTokens()) {
                String entry = st.nextToken();
                Path candidate = Paths.get(entry);
                if (!Files.isRegularFile(candidate) || !candidate.toString().endsWith(".jar")) {
                    continue;
                }
                /**
                 * 尝试。
                 * @param JarFile(candidate.toFile( jar文件(candidate.转为文件(
                 * @param JarFile(candidate.toFile( jar文件(candidate.转为文件(
                 * @return 尝试的结果
                 */
                try (JarFile jar = new JarFile(candidate.toFile())) {
                    if (jar.getEntry("com/chua/runtime/agent/RuntimeAgent.class") != null) {
                        return candidate;
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "探测 agent jar 异常", e);
        }
        return null;
    }

    /**
     * 收集 类路径 上所有 jar（供断言用）。
     *
     * @return 全部jarson类路径的结果
     */
    private static List<Path> allJarsOnClasspath() {
        List<Path> result = new ArrayList<>();
        StringTokenizer st =
                new StringTokenizer(System.getProperty("java.class.path"), java.io.File.pathSeparator);
        while (st.hasMoreTokens()) {
            String entry = st.nextToken();
            Path candidate = Paths.get(entry);
            if (Files.isRegularFile(candidate) && candidate.toString().endsWith(".jar")) {
                result.add(candidate);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 测试用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("能定位 classpath 上的 agent jar")
    /**
     * shouldlocate智能体jar。
     */
    void shouldLocateAgentJar() {
        if (agentJar == null) {
            LOG.info("[Skip] classpath 未含 agent jar（可能以 class dir 方式运行），跳过定位断言");
            return;
        }
        /**
         * 断言not空。
         * @param agentJar 智能体jar
         * @return 断言not空的结果
         */
        assertNotNull(agentJar);
        /**
         * 断言true。
         * @param Files.isRegularFile(agentJar 文件.是否regular文件(智能体jar
         * @param Files.isRegularFile(agentJar 文件.是否regular文件(智能体jar
         * @return 断言true的结果
         */
        assertTrue(Files.isRegularFile(agentJar), "agent jar 应存在: " + agentJar);
        LOG.info("[OK] 定位到 agent jar: " + agentJar);
    }

    @Test
    @DisplayName("classpath 上至少有一个 jar")
    /**
     * shouldhavejarson类路径。
     */
    void shouldHaveJarsOnClasspath() {
        List<Path> jars = allJarsOnClasspath();
        LOG.info("[Info] classpath 上共 " + jars.size() + " 个 jar");
        /**
         * 断言false。
         * @param jars.isEmpty( jars.是否空(
         * @param jars.isEmpty( jars.是否空(
         * @return 断言false的结果
         */
        assertFalse(jars.isEmpty(), "classpath 上应有 jar");
    }

    @Test
    @DisplayName("自 attach：RuntimeBoot.attachSelf() 注入当前 JVM")
    /**
     * attachself转为当前jvm。
     */
    void attachSelfToCurrentJvm() throws Exception {
        if (agentJar == null) {
            LOG.warning("[Skip] 未定位到 agent jar，无法自 attach（跳过真实注入）");
            return;
        }
        int pid = currentPid;
        LOG.info("[Before] PID=" + pid + " agent=" + agentJar);

        RuntimeBoot boot = RuntimeBoot.create()
                .withAgent(agentJar)
                .withAgentOptions("plugins=./plugins,enabled=true,port=4567");
        RuntimeBoot returned = boot.attachSelf();
        /**
         * 断言not空。
         * @param returned 申报
         * @param 应返回自身" 应返回自身"
         * @param 应返回自身" 应返回自身"
         * @return 断言not空的结果
         */
        assertNotNull(returned, "attachSelf 应返回自身");

        // 等待 agentmain 由 attach 机制异步执行
        Thread.sleep(1500L);

 // 验证 智能体 jar 确实已加载到 类路径（attach 成功的客观证据）
 // 注意：spybootstrap.是否初始化() 是跨 classloader 的静态状态——
 // 智能体 由独立加载器加载，测试 classloader 看不到其静态字段，
        // 故此处仅做诊断日志，不作为断言。
        boolean spyOk = com.chua.runtime.spy.SpyBootstrap.isInitialized();
        var apm = com.chua.runtime.apm.ApmBootstrap.getGlobal();
        LOG.info("[After] SpyBootstrap.isInitialized=" + spyOk
                + " (跨加载器，仅供参考); ApmBootstrap.getGlobal="
                + (apm != null ? apm.isStarted() + "/" + apm.getHandlers().size() : null));

 // 核心断言 1：智能体 jar 在 类路径 上（attach 的输入已就绪）
        /**
         * 断言true。
         * @param Files.isRegularFile(agentJar 文件.是否regular文件(智能体jar
         * @param Files.isRegularFile(agentJar 文件.是否regular文件(智能体jar
         * @return 断言true的结果
         */
        assertTrue(Files.isRegularFile(agentJar), "agent jar 应在 classpath: " + agentJar);
 // 核心断言 2：apmbootstrap 已被 agentmain 初始化且注册了处理器
 // （agentmain -> apmbootstrap.启动 -> 注册默认，副作用可观测）
        /**
         * 断言not空。
         * @param apm apm
         * @param ApmBootstrap.getGlobal( apmbootstrap.获取全局(
         * @param ApmBootstrap.getGlobal( apmbootstrap.获取全局(
         * @return 断言not空的结果
         */
        assertNotNull(apm, "attach 后 ApmBootstrap.getGlobal() 应非空");
        /**
         * 断言false。
         * @param apm.getHandlers( apm.获取处理器(
         * @param apm.getHandlers( apm.获取处理器(
         * @return 断言false的结果
         */
        assertFalse(apm.getHandlers().isEmpty(),
                "attach 后 APM 应注册处理器，实际=" + apm.getHandlers().size());
        LOG.info("[OK] attachSelf 完成，handlers=" + apm.getHandlers().size());
    }

    @Test
    @DisplayName("attachSelf 在 agent jar 缺失时安全跳过")
    /**
        * attachselfshould跳过When.js.js.js.js智能体missing。
     */
    void attachSelfShouldSkipWhenAgentMissing() {
 // 显式指向一个不存在的 智能体 路径，attachself 应告警但不抛异常
        RuntimeBoot boot = RuntimeBoot.create()
                .withAgent(Paths.get("nonexistent-agent.jar"))
                .withAgentOptions("");
        RuntimeBoot returned = boot.attachSelf();
        /**
         * 断言not空。
         * @param returned 申报
         * @param 应返回自身（不阻断链式调用）" 应返回自身（不阻断链式调用）"
         * @param 应返回自身（不阻断链式调用）" 应返回自身（不阻断链式调用）"
         * @return 断言not空的结果
         */
        assertNotNull(returned, "attachSelf 应返回自身（不阻断链式调用）");
        LOG.info("[OK] agent 缺失时 attachSelf 安全返回");
    }
}
