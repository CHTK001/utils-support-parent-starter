package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

/**
 * 状态命令 — 显示系统与 JVM 概览。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class StatusCommand implements Command {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String[] aliases() {
        return new String[]{"stats", "uptime"};
    }

    @Override
    public String description() {
        return "显示系统与 JVM 状态";
    }

    @Override
    public int execute(String[] args, Console console) {
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeMx = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean osMx = ManagementFactory.getOperatingSystemMXBean();

        console.header("运行时状态");
        console.println("Uptime      : " + formatDuration(runtimeMx.getUptime()));
        console.println("OS          : " + osMx.getName() + " " + osMx.getVersion() + " (" + osMx.getArch() + ")");
        console.println("CPUs        : " + runtime.availableProcessors());
        console.println("Memory used : " + toMb(runtime.totalMemory() - runtime.freeMemory()) + " MB");
        console.println("Memory total: " + toMb(runtime.totalMemory()) + " MB");
        console.println("Memory max  : " + toMb(runtime.maxMemory()) + " MB");
        console.println("Classpath   : " + System.getProperty("java.class.path", ""));
        return 0;
    }

    /**
     * 字节数转换为 MB。
     *
     * @param bytes 字节数
     * @return MB 值
     */
    private long toMb(long bytes) {
        return bytes / (1024 * 1024);
    }

    /**
     * 时长格式化。
     *
     * @param millis 毫秒
     * @return 格式化字符串
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m " + (seconds % 60) + "s";
    }
}