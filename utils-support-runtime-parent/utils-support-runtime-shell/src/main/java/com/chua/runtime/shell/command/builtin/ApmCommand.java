package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.util.List;

/**
 * APM 命令 — 查看 APM 拦截数据。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ApmCommand implements Command {

    /**
     * APM 启动器
     */
    private final ApmBootstrap apm;

    /**
     * 创建 APM 命令。
     *
     * @param apm APM 启动器
     */
    public ApmCommand(ApmBootstrap apm) {
        this.apm = apm;
    }

    @Override
    public String name() {
        return "apm";
    }

    @Override
    public String[] aliases() {
        return new String[]{"logs", "net", "file", "trace"};
    }

    @Override
    public String description() {
        return "查看 APM 拦截数据 (apm logs/net/file/trace)";
    }

    @Override
    public int execute(String[] args, Console console) {
        if (apm == null) {
            console.error("APM 未启动");
            return 1;
        }
        if (args.length == 0) {
            console.println(apm.status());
            return 0;
        }

        String subCmd = args[0].toLowerCase();
        switch (subCmd) {
            case "logs" -> showLogs(console, args);
            case "net" -> showNet(console, args);
            case "file" -> showFile(console, args);
            case "trace" -> showTrace(console, args);
            default -> console.error("未知子命令: " + args[0] + "，支持: logs/net/file/trace");
        }
        return 0;
    }

    /**
     * 显示日志记录。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void showLogs(Console console, String[] args) {
        LogHandler handler = apm.getHandler(LogHandler.class);
        if (handler == null) {
            console.error("LogHandler 未加载");
            return;
        }
        List<LogHandler.LogEntry> entries = handler.getLogEntries();
        console.header("日志记录 (" + entries.size() + ")");
        int show = Math.min(entries.size(), 20);
        int start = entries.size() - show;
        for (int i = start; i < entries.size(); i++) {
            LogHandler.LogEntry e = entries.get(i);
            console.println(String.format("[%s] %s  %s", e.getLevel(), e.getClassName(), e.getMessage()));
        }
    }

    /**
     * 显示网络记录。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void showNet(Console console, String[] args) {
        NetHandler handler = apm.getHandler(NetHandler.class);
        if (handler == null) {
            console.error("NetHandler 未加载");
            return;
        }
        List<NetHandler.NetRecord> records = handler.getRecords();
        console.header("网络记录 (" + records.size() + ")");
        int show = Math.min(records.size(), 20);
        int start = records.size() - show;
        for (int i = start; i < records.size(); i++) {
            NetHandler.NetRecord r = records.get(i);
            console.println(String.format("[%s] %s  %s -> %s  status=%s",
                    r.getProtocol(), r.getClassName(),
                    r.getTargetAddress(), r.getTargetAddress(), r.getStatus()));
        }
    }

    /**
     * 显示文件记录。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void showFile(Console console, String[] args) {
        FileHandler handler = apm.getHandler(FileHandler.class);
        if (handler == null) {
            console.error("FileHandler 未加载");
            return;
        }
        List<FileHandler.FileRecord> records = handler.getRecords();
        console.header("文件记录 (" + records.size() + ")");
        int show = Math.min(records.size(), 20);
        int start = records.size() - show;
        for (int i = start; i < records.size(); i++) {
            FileHandler.FileRecord r = records.get(i);
            console.println(String.format("[%s] %s  %s", r.getOperation(), r.getClassName(), r.getPath()));
        }
    }

    /**
     * 显示追踪记录。
     *
     * @param console 控制台
     * @param args    参数
     */
    private void showTrace(Console console, String[] args) {
        TraceHandler handler = apm.getHandler(TraceHandler.class);
        if (handler == null) {
            console.error("TraceHandler 未加载");
            return;
        }
        List<TraceHandler.Span> spans = handler.getSpans();
        console.header("追踪 Span (" + spans.size() + ")");
        int show = Math.min(spans.size(), 20);
        int start = spans.size() - show;
        for (int i = start; i < spans.size(); i++) {
            TraceHandler.Span s = spans.get(i);
            console.println(String.format("[%s] %s  %s.%s  duration=%dms  status=%s",
                    s.getSpanId(), s.getParentSpanId() != null ? "└" : "─",
                    s.getClassName(), s.getMethodName(),
                    s.getDuration(), s.getStatus()));
        }
    }
}