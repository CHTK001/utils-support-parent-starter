package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * 线程列表命令 — 显示当前 JVM 线程状态。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ThreadsCommand implements Command {

    @Override
    /** Name */
    public String name() {
        return "threads";
    }

    @Override
    /** Aliases */
    public String[] aliases() {
        return new String[]{"th", "dump"};
    }

    @Override
    /** Description */
    public String description() {
        return "显示 JVM 线程列表与状态";
    }

    @Override
    /** 执行 */
    public int execute(String[] args, Console console) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] ids = bean.getAllThreadIds();
        console.header("线程列表 (" + ids.length + ")");
        console.println("编号  |  名称  |  状态  |  CPU  |  Blocked");
        console.println("------|---------|---------|-------|---------");
        for (long id : ids) {
            ThreadInfo info = bean.getThreadInfo(id, 3);
            if (info == null) {
                continue;
            }
            String locked = info.getLockedSynchronizers().length > 0
                    ? String.valueOf(info.getLockedSynchronizers().length)
                    : "";
            console.println(String.format("%-6d | %-10s | %-8s | %d   | %s",
                    id, truncate(info.getThreadName(), 10),
                    info.getThreadState().toString(), bean.getThreadCpuTime(id), locked));
        }
        console.blank();
        console.println("Blocked : " + bean.getThreadCount() + " (deprecated counters removed in Java 25)");
        return 0;
    }

    /**
     * 截断字符串到指定长度。
     *
     * @param s    原始字符串
     * @param max  最大长度
     * @return 截断结果
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + ".";
    }
}