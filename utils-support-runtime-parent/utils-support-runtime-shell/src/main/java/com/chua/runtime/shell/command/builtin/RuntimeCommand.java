package com.chua.runtime.shell.command.builtin;

import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.runtime.core.manager.RuntimeInstance;
import com.chua.runtime.core.manager.RuntimeManager;
import com.chua.runtime.core.model.RuntimeStatus;
import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.util.List;

/**
 * 运行时命令 — 显示 Runtime 管理状态。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RuntimeCommand implements Command {

    /**
     * 主命令名
     */
    private static final String CMD_NAME = "runtime";

    /**
     * "list" 子命令
     */
    private static final String SUB_LIST = "list";

    /**
     * "start" 子命令
     */
    private static final String SUB_START = "start";

    /**
     * "stop" 子命令
     */
    private static final String SUB_STOP = "stop";

    /**
     * "status" 子命令
     */
    private static final String SUB_STATUS = "status";

    /**
     * "log" 子命令
     */
    private static final String SUB_LOG = "log";

    /**
     * PID 显示前缀
     */
    private static final String PID_PREFIX = "PID: ";

    /**
     * 运行时管理器
     */
    private final RuntimeManager manager;

    /**
     * 创建运行时命令。
     *
     * @param manager 运行时管理器
     */
    public RuntimeCommand(RuntimeManager manager) {
        this.manager = manager;
    }

    @Override
    /** Name */
    public String name() {
        return CMD_NAME;
    }

    @Override
    /** Aliases */
    public String[] aliases() {
        return new String[]{SUB_LIST, "services"};
    }

    @Override
    /** Description */
    public String description() {
        return "显示运行时管理状态 (runtime list/start/stop)";
    }

    @Override
    /** 执行 */
    public int execute(String[] args, Console console) {
        if (args.length == 0) {
            listAll(console);
            return 0;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case SUB_LIST -> listAll(console);
            case SUB_START -> startArtifact(console, args);
            case SUB_STOP -> stopArtifact(console, args);
            case SUB_STATUS -> statusArtifact(console, args);
            case SUB_LOG -> tailLog(console, args);
            default -> console.error("未知子命令: " + sub + "，支持: list/start/stop/status/log");
        }
        return 0;
    }

    /**
     * 列出所有工件。
     *
     * @param console 控制台
     */
    private void listAll(Console console) {
        console.header("运行工件");
        List<String> ids = manager.getArtifactIds();
        console.println("ID | 状态 | 实例");
        console.println("----|-------|-----");
        for (String id : ids) {
            RuntimeStatus status = manager.status(id);
            RuntimeInstance inst = manager.getInstance(id);
            console.println(String.format("%-20s | %-8s | %s", id,
                    status.toString(), inst != null ? PID_PREFIX + inst.pid() : "无"));
        }
        if (ids.isEmpty()) {
            console.println("  （无已注册工件）");
        }
    }

    /**
     * 启动工件。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void startArtifact(Console console, String[] args) {
        if (args.length < 2) {
            console.error("用法: runtime start &lt;id&gt;");
            return;
        }
        String id = args[1];
        manager.start(id);
        console.success("已启动: " + id);
    }

    /**
     * 停止工件。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void stopArtifact(Console console, String[] args) {
        if (args.length < 2) {
            console.error("用法: runtime stop &lt;id&gt;");
            return;
        }
        String id = args[1];
        manager.stop(id);
        console.success("已停止: " + id);
    }

    /**
     * 查看工件状态。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void statusArtifact(Console console, String[] args) {
        if (args.length < 2) {
            console.error("用法: runtime status &lt;id&gt;");
            return;
        }
        String id = args[1];
        RuntimeStatus status = manager.status(id);
        console.println("工件: " + id + " | 状态: " + status);
    }

    /**
     * 查看工件日志。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void tailLog(Console console, String[] args) {
        if (args.length < 2) {
            console.error("用法: runtime log &lt;id&gt;");
            return;
        }
        String id = args[1];
        manager.tailLog(id, new LineCallback() {
            @Override
            /** OnLine */
            public void onLine(String line) {
                console.println(line);
            }

            @Override
            /** On记录错误 */
            public void onError(String key, Throwable e) {
                console.error("日志读取失败: " + e.getMessage());
            }
        });
    }
}