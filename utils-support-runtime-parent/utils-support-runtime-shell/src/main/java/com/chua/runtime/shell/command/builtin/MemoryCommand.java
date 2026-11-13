package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;

/**
 * 内存信息命令 — 显示 JVM 内存使用详情。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MemoryCommand implements Command {

    /**
     * 名称
    */
    @Override
    public String name() {
        return "memory";
    }

    /**
     * Aliases
    */
    @Override
    public String[] aliases() {
        return new String[]{"mem", "gc"};
    }

    /**
     * Description
    */
    @Override
    public String description() {
        return "显示 JVM 内存池与 GC 信息";
    }

    /**
     * 执行
    */
    @Override
    public int execute(String[] args, Console console) {
        MemoryMXBean memMx = ManagementFactory.getMemoryMXBean();
        console.header("堆内存");
        console.println("已用: " + toMb(memMx.getHeapMemoryUsage().getUsed()));
        console.println("初始: " + toMb(memMx.getHeapMemoryUsage().getInit()));
        console.println("已提交: " + toMb(memMx.getHeapMemoryUsage().getCommitted()));
        console.println("最大: " + toMb(memMx.getHeapMemoryUsage().getMax()));
        console.blank();

        console.header("内存池");
        console.println("名称 | 已用 | 已提交 | 最大");
        console.println("-----|------|--------|-----");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            var usage = pool.getUsage();
            console.println(String.format("%-20s | %8s | %8s | %8s",
                    pool.getName(), toMb(usage.getUsed()),
                    toMb(usage.getCommitted()), toMb(usage.getMax())));
        }
        console.blank();

        console.header("GC");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            console.println(gc.getName() + ": " + gc.getCollectionCount() + " 次, "
                    + toMs(gc.getCollectionTime()) + " ms");
        }
        return 0;
    }

    /**
     * 转为mb
     *
     * @param bytes bytes
     * @return 转为mb的结果
     */
    private String toMb(long bytes) {
        // [P3C A20 豁免]
        return String.valueOf(bytes / (1024L * 1024L));
    }

    /**
     * 转为ms
     *
     * @param ms ms
     * @return 转为ms的结果
     */
    private String toMs(long ms) {
        return String.valueOf(ms);
    }
}