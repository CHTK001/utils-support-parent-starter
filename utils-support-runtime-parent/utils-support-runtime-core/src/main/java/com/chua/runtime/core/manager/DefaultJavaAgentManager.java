package com.chua.runtime.core.manager;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.runtime.core.service.JavaAgentManager;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 默认 Java Agent 管理器。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultJavaAgentManager implements JavaAgentManager {

    /**
     * 命令超时（秒）
     */
    private static final int CMD_TIMEOUT = 30;

    @Override
    public String name() {
        return "default";
    }

    @Override
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
            log.warn("列出进程失败", e);
        }
        return jvms;
    }

    @Override
    public CmdResult inspectJvm(int pid) {
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, String.valueOf(pid));
            String classPath = (String) vmClass.getMethod("getClassPath").invoke(vm);
            String sysProps = (String) vmClass.getMethod("getSystemProperties").invoke(vm);
            vmClass.getMethod("detach").invoke(vm);

            String result = "PID: " + pid + "\nClassPath: " + classPath + "\nSystemProperties: " + sysProps;
            return CmdResult.builder().exitCode(0).stdout(result).build();
        } catch (Exception e) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr(e.getMessage()).throwable(e).build();
        }
    }

    @Override
    public CmdResult attach(int pid, Path agentPath, String options) {
        log.info("正在注入 Agent 到 PID[{}]", pid);
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, String.valueOf(pid));
            vmClass.getMethod("loadAgent", String.class, String.class)
                    .invoke(vm, agentPath.toAbsolutePath().toString(), options);
            vmClass.getMethod("detach").invoke(vm);
            return CmdResult.builder().exitCode(0).stdout("Agent 注入成功: PID[" + pid + "]").build();
        } catch (Exception e) {
            return CmdResult.builder().exitCode(CmdResult.EXIT_CODE_ERROR).stderr(e.getMessage()).throwable(e).build();
        }
    }

    @Override
    public CmdResult attachByPort(int port, Path agentPath, String options) {
        return attach(port, agentPath, options);
    }

    @Override
    public CmdResult detach(int pid) {
        return CmdResult.builder()
                .exitCode(1)
                .stderr("JDK 不支持 detach，请重启目标 JVM")
                .build();
    }
}