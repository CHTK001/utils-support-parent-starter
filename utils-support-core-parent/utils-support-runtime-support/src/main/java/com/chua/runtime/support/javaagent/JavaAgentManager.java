package com.chua.runtime.support.javaagent;

import com.chua.common.support.lang.cmd.CmdResult;

/**
 * Java 智能体 管理器 — 管理 Java 智能体 的附加、卸载和远程注入。
 *
 * <p>核心能力：</p>
 * <ul>
 *   <li>{@link #attach} — 将 Agent JAR 注入到运行中的 JVM（按 PID 或端口）</li>
 *   <li>{@link #detach} — 从目标 JVM 卸载 Agent（需目标 JVM 配合）</li>
 *   <li>{@link #listPids} — 列出本机所有 Java 进程</li>
 *   <li>{@link #inspectJvm} — 检查目标 JVM 的类路径、Agent 状态等</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * JavaAgentManager agentMgr = new DefaultJavaAgentManager();
 *
 * // 列出 Java 进程
 * Map<Integer, String> jvms = agentMgr.listPids();
 * for (Map.Entry<Integer, String> entry : jvms.entrySet()) {
 *     System.out.println(entry.getKey() + " - " + entry.getValue());
 * }
 *
 * // 将 Agent 注入到 PID 12345 的 JVM
 * agentMgr.attach(12345, agentJarPath, "key=value");
 *
 * // 获取目标 JVM 信息
 * agentMgr.inspectJvm(12345);
 * }</pre>* 智能体mgr.inspectjvm(12345);
 * }</pre>
 *
 * <h3>Agent JAR 要求</h3>
 * <p>要注入的 JAR 必须包含以下 Manifest 条目：</p>
 * <pre>
 * Premain-Class: com.example.MyAgent
 * Agent-Class: com.example.MyAgent
 * Can-Redefine-Classes: true
 * Can-Retransform-Classes: true
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface JavaAgentManager extends AutoCloseable {

    /**
     * 获取管理器名称。
     *
     * @return 名称标识
     */
    String name();

    /**
     * 列出本机所有 Java 进程。
     *
     * <p>返回 PID 到进程描述（主类名或进程名）的映射。</p>
     *
     * @return Java 进程映射
     */
    java.util.Map<Integer, String> listPids();

    /**
     * 检查目标 JVM 的运行时信息。
     *
     * <p>返回目标 JVM 的主类名、类路径、已加载的 Agent 等信息。</p>
     *
     * @param pid 目标进程 标识
     * @return 检查结果
     */
    CmdResult inspectJvm(int pid);

    /**
     * 将 智能体 JAR 注入到目标 JVM。
     *
     * <p>使用 JDK 自带的 VirtualMachine.attach() 机制，
     * 调用目标 JVM 的 agentmain() 入口。</p>
     *
     * @param pid       目标进程 标识
     * @param agentPath 智能体 JAR 的本地路径
     * @param options   智能体 参数（可为空）
     * @return 注入结果
     */
    CmdResult attach(int pid, java.nio.file.Path agentPath, String options);

    /**
     * 从目标 JVM 卸载 智能体。
     *
     * <p>注意：JDK 不直接支持 detach，此方法通过向目标 JVM
     * 发送信号或调用已注册的反向接口来实现卸载。</p>
     *
     * @param pid 目标进程 标识
     * @return 卸载结果
     */
    CmdResult detach(int pid);

    /**
     * 通过本地端口附加到远程 JVM（需目标 JVM 已启用 -XX:+perfattach）。
     *
     * @param port      目标 JVM 的 attach API 端口（通常与 PID 相同）
     * @param agentPath 智能体 JAR 路径
     * @param options   智能体 参数
     * @return 注入结果
     */
    CmdResult attachByPort(int port, java.nio.file.Path agentPath, String options);

    @Override
    /**
     * 关闭
    */
    default void close() throws Exception {
    }
}