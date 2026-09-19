package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.runtime.core.service.JavaAgentManager;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 默认 Java 智能体 管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultJavaAgentManager implements JavaAgentManager {


    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(DefaultJavaAgentManager.class.getName());
    /**
     * 命令超时（秒）
     */
    private static final int CMD_TIMEOUT = 30;

    @Override
    /**
     * 名称
    */
    public String name() {
        return "default";
    }

    @Override
    /**
     * 列表pids
    */
    public Map<Integer, String> listPids() {
        Map<Integer, String> jvms = new HashMap<>();
        try {
            CmdResult r = CmdExecutors.execute(
                    "ps -eo pid,comm,args | grep -E '[j]ava|sun.tools.launcher' | grep -v grep",
                    10, TimeUnit.SECONDS);
            if (r.isSuccess()) {
                for (String line : r.getStdout().split("\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            jvms.put(Integer.parseInt(parts[0]), line.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, String.format("列出进程失败", e));
        }
        return jvms;
    }

    @Override
    /**
     * inspectjvm
    */
    public CmdResult inspectJvm(int pid) {
        try {
            Class<?> vmClass = ReflectUtils.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = ReflectUtils.invokeStatic(vmClass, "attach", Object.class, new Class<?>[]{String.class}, String.valueOf(pid));
            String classPath = (String) ReflectUtils.invoke(vm, "getClassPath", String.class);
            String sysProps = (String) ReflectUtils.invoke(vm, "getSystemProperties", String.class);
            ReflectUtils.invoke(vm, "detach", void.class);
            String result = "PID: " + pid + "\nClassPath: " + classPath + "\nSystemProperties: " + sysProps;
            return CmdResult.builder().exitCode(0).stdout(result).build();
        } catch (Exception e) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr(e.getMessage()).throwable(e).build();
        }
    }

    @Override
    /**
     * Attach
    */
    public CmdResult attach(int pid, Path agentPath, String options) {
        LOG.log(Level.INFO, String.format("正在注入 Agent 到 PID[%s]", pid));
        try {
            Class<?> vmClass = ReflectUtils.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = ReflectUtils.invokeStatic(vmClass, "attach", Object.class, new Class<?>[]{String.class}, String.valueOf(pid));
            ReflectUtils.invoke(vm, "loadAgent", void.class, new Class<?>[]{String.class, Object.class}, agentPath.toAbsolutePath().toString(), options);
            ReflectUtils.invoke(vm, "detach", void.class);
            return CmdResult.builder().exitCode(0).stdout("Agent 注入成功: PID[" + pid + "]").build();
        } catch (Exception e) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr(e.getMessage()).throwable(e).build();
        }
    }

    @Override
    /**
     * attachby端口
    */
    public CmdResult attachByPort(int port, Path agentPath, String options) {
        return attach(port, agentPath, options);
    }

    @Override
    /**
     * Detach
    */
    public CmdResult detach(int pid) {
        return CmdResult.builder()
                .exitCode(1)
                .stderr("JDK 不支持 detach，请重启目标 JVM")
                .build();
    }
}