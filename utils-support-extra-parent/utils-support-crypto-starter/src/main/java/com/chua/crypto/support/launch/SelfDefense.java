package com.chua.crypto.support.launch;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Locale;

/**
* 运行时自我防护（零依赖，随引导器注入）
*
* <p>针对"运行期提取字节码"的常见手段做检测与响应：
*
* <h2>1. Agent 注入检测</h2>
* 检查 JVM 输入参数中的 {@code -javaagent/-agentpath/-agentlib}；
* 加密发行包正常启动不应携带任何 Agent。
*
* <h2>2. 字节码工具包加载扫描</h2>
* 后台守护线程周期性扫描线程栈中已加载类的包名，命中已知注入/转储工具
* （bytebuddy、Javassist、Attach API、Jacoco 等）即判定为可疑注入。
*
* <h2>3. 防护模式</h2>
* {@code -Dchua.crypto.guard=off|warn|strict}（默认 warn）：
* <ul>
*   <li>off — 关闭全部检测</li>
*   <li>warn — 仅输出告警</li>
*   <li>strict — 命中后立即终止进程</li>
* </ul>
*
* <p>建议同时在发行脚本追加 JVM 参数 {@code -XX:+DisableAttachMechanism}
* 从源头关闭动态 attach 通道（进程内无法代为设置）。
*
* @author CH
* @since 2026-08-26
 */
public final class SelfDefense {

    /**
    * 防护模式系统属性
    */
    public static final String PROP_GUARD = "chua.crypto.guard";

    /**
    * 已知注入/转储工具的包名前缀特征
    */
    private static final String[] SUSPICIOUS_PACKAGES = {
            "net.bytebuddy",
            "org.jacoco.agent",
            "com.sun.tools.attach",
            "javassist.util.proxy",
            "org.jmockit",
            "org.mockito.internal.creation.bytebuddy",
    };

    /**
    * 私有构造
    */
    private SelfDefense() {
    }

    /**
    * 安装防护：立即执行一次 Agent 参数检测，并按需启动后台扫描线程
    */
    public static void install() {
        String mode = guardMode();
        if ("off".equals(mode)) {
            return;
        }
        checkAgentArguments(mode);
        startToolkitScanner(mode);
    }

    /**
    * 解析防护模式
    *
    * @return off / warn / strict
    */
    private static String guardMode() {
        String mode = System.getProperty(PROP_GUARD, "warn");
        return mode == null ? "warn" : mode.toLowerCase(Locale.ROOT);
    }

    /**
    * 检测 JVM 输入参数中的 Agent 注入
    *
    * @param mode 防护模式
    */
    private static void checkAgentArguments(String mode) {
        try {
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            List<String> arguments = runtime.getInputArguments();
            for (String argument : arguments) {
                String lower = argument.toLowerCase(Locale.ROOT);
                if (lower.startsWith("-javaagent")
                        || lower.startsWith("-agentpath")
                        || lower.startsWith("-agentlib")) {
                    respond(mode, "检测到 agent 注入参数: " + argument);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 受限环境无法读取运行时 Bean 时跳过
        }
    }

    /**
    * 启动后台工具包加载扫描守护线程
    *
    * @param mode 防护模式
    */
    private static void startToolkitScanner(String mode) {
        Thread scanner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException e) {
                    return;
                }
                scanStacks(mode);
            }
        }, "chua-crypto-guard");
        scanner.setDaemon(true);
        scanner.start();
    }

    /**
    * 扫描全部线程栈帧所属类的包名是否命中特征
    *
    * @param mode 防护模式
    */
    private static void scanStacks(String mode) {
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                for (StackTraceElement element : thread.getStackTrace()) {
                    String className = element.getClassName() == null ? "" : element.getClassName();
                    for (String suspicious : SUSPICIOUS_PACKAGES) {
                        if (className.startsWith(suspicious)) {
                            respond(mode, "检测到可疑注入工具类: " + className);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 扫描失败不影响应用运行
        }
    }

    /**
    * 按模式响应安全事件
    *
    * @param mode    防护模式
    * @param message 事件描述
    */
    private static void respond(String mode, String message) {
        System.err.println("[chua-crypto-guard] " + message);
        if ("strict".equals(mode)) {
            System.err.println("[chua-crypto-guard] strict 模式：终止进程");
            Runtime.getRuntime().halt(96);
        }
    }
}
