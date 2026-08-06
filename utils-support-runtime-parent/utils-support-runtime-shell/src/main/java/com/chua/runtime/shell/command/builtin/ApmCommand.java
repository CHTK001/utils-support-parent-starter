package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.LogEntry;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.util.ArrayList;
import java.util.List;

/**
 * APM 命令 — 动态展示已加载的 APM 处理器及其数据。
 *
 * <p>子命令从 ApmBootstrap 实际加载的 Plugin 动态生成：
 * 主命令 apm 显示总览，apm &lt;handler&gt; 查看具体 handler 数据。</p>
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
        // 动态别名: handler 名称
        List<String> aliases = new ArrayList<>();
        if (apm != null) {
            for (Plugin p : apm.getHandlers()) {
                aliases.add(p.name().replace("-handler", ""));
            }
        }
        return aliases.toArray(new String[0]);
    }

    @Override
    public String description() {
        return "查看 APM 拦截数据 (apm [handler])";
    }

    @Override
    public List<String> complete(String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 0 || args.length == 1) {
            completions.add("list");
            completions.add("status");
            completions.add("help");
            if (apm != null) {
                for (Plugin p : apm.getHandlers()) {
                    String shortName = p.name().replace("-handler", "");
                    if (shortName.equals(args.length == 0 ? "" : args[0])) {
                        continue;
                    }
                    completions.add(shortName);
                }
            }
        }
        return completions;
    }

    @Override
    public int execute(String[] args, Console console) {
        if (apm == null) {
            console.error("APM 未启动");
            return 1;
        }
        if (args.length == 0) {
            showOverview(console);
            return 0;
        }

        String subCmd = args[0].toLowerCase();
        switch (subCmd) {
            case "list":
                showOverview(console);
                break;
            case "status":
                console.println(apm.status());
                break;
            case "help":
                showHelp(console);
                break;
            default:
                Plugin handler = findHandler(subCmd);
                if (handler == null) {
                    console.error("未知子命令: " + args[0] + "，可用: list/status/help/" + String.join("/", handlerNames()));
                    return 1;
                }
                return showHandler(handler, console, args);
        }
        return 0;
    }

    /**
     * 显示 APM 总览。
     *
     * @param console 控制台
     */
    private void showOverview(Console console) {
        console.header("APM 处理器总览 (" + apm.getHandlers().size() + ")");
        console.println("名称                | 状态   | 描述");
        console.println("--------------------|--------|------------------------");
        for (Plugin p : apm.getHandlers()) {
            console.println(String.format("%-20s | %-7s | %s",
                    p.name(),
                    p.isRunning() ? "RUNNING" : "STOPPED",
                    p.status()));
        }
        console.blank();
        console.println("提示: 输入 'apm " + handlerNames().get(0) + "' 查看详细数据");
    }

    /**
     * 显示帮助。
     *
     * @param console 控制台
     */
    private void showHelp(Console console) {
        console.header("APM 命令帮助");
        console.println("apm              - 显示所有处理器总览");
        console.println("apm list         - 列出所有处理器");
        console.println("apm status       - 显示所有处理器状态");
        console.println("apm help         - 显示帮助");
        for (String name : handlerNames()) {
            console.println("apm " + name + " [N]  - 查看 " + name + " 数据 (可选 N=最近 N 条)");
        }
    }

    /**
     * 显示具体 handler 的数据。
     *
     * @param handler 处理器
     * @param console 控制台
     * @param args    参数
     * @return 退出码
     */
    private int showHandler(Plugin handler, Console console, String[] args) {
        int limit = 20;
        if (args.length > 1) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (handler instanceof LogHandler) {
            showLogs((LogHandler) handler, console, limit);
        } else if (handler instanceof NetHandler) {
            showNet((NetHandler) handler, console, limit);
        } else if (handler instanceof FileHandler) {
            showFile((FileHandler) handler, console, limit);
        } else if (handler instanceof TraceHandler) {
            showTrace((TraceHandler) handler, console, limit);
        } else {
            console.println(handler.status());
        }
        return 0;
    }

    /**
     * 显示日志条目。
     */
    private void showLogs(LogHandler handler, Console console, int limit) {
        List<LogEntry> entries = handler.getLogEntries();
        console.header("日志记录 (" + entries.size() + ", 最近 " + limit + ")");
        console.println("时间戳      | 级别 | Logger                       | 类名");
        console.println("------------|------|------------------------------|-------------");
        int start = Math.max(0, entries.size() - limit);
        for (int i = start; i < entries.size(); i++) {
            LogEntry e = entries.get(i);
            console.println(String.format("%-12d | %-4s | %-30s | %s",
                    e.getTimestamp(),
                    e.getLevel(),
                    truncate(e.getLogger(), 30),
                    e.getClassName()));
        }
        if (entries.isEmpty()) {
            console.println("  （暂无日志）");
        }
    }

    /**
     * 显示网络记录。
     */
    private void showNet(NetHandler handler, Console console, int limit) {
        List<NetHandler.NetRecord> records = handler.getRecords();
        console.header("网络记录 (" + records.size() + ", 最近 " + limit + ")");
        console.println("协议  | 目标地址              | 类名");
        console.println("------|------------------------|-------------");
        int start = Math.max(0, records.size() - limit);
        for (int i = start; i < records.size(); i++) {
            NetHandler.NetRecord r = records.get(i);
            console.println(String.format("%-5s | %-22s | %s",
                    r.getProtocol(),
                    truncate(r.getTargetAddress(), 22),
                    r.getClassName()));
        }
        if (records.isEmpty()) {
            console.println("  （暂无网络记录）");
        }
    }

    /**
     * 显示文件记录。
     */
    private void showFile(FileHandler handler, Console console, int limit) {
        List<FileHandler.FileRecord> records = handler.getRecords();
        console.header("文件记录 (" + records.size() + ", 最近 " + limit + ")");
        console.println("操作  | 路径                          | 类名");
        console.println("------|--------------------------------|-------------");
        int start = Math.max(0, records.size() - limit);
        for (int i = start; i < records.size(); i++) {
            FileHandler.FileRecord r = records.get(i);
            console.println(String.format("%-5s | %-30s | %s",
                    r.getOperation(),
                    truncate(r.getPath(), 30),
                    r.getClassName()));
        }
        if (records.isEmpty()) {
            console.println("  （暂无文件记录）");
        }
    }

    /**
     * 显示链路追踪 Span。
     */
    private void showTrace(TraceHandler handler, Console console, int limit) {
        List<TraceHandler.Span> spans = handler.getSpans();
        console.header("追踪 Span (" + spans.size() + ", 最近 " + limit + ")");
        console.println("Span ID            | 父 Span ID       | 类.方法                     | 耗时(ms) | 状态");
        console.println("-------------------|------------------|-----------------------------|----------|--------");
        int start = Math.max(0, spans.size() - limit);
        for (int i = start; i < spans.size(); i++) {
            TraceHandler.Span s = spans.get(i);
            console.println(String.format("%-18s | %-17s | %-28s | %-9d | %s",
                    truncate(s.getSpanId(), 18),
                    s.getParentSpanId() != null ? truncate(s.getParentSpanId(), 17) : "(root)",
                    truncate(s.getClassName() + "." + s.getMethodName(), 28),
                    s.getDuration(),
                    s.getStatus()));
        }
        if (spans.isEmpty()) {
            console.println("  （暂无 Span）");
        }
    }

    /**
     * 根据简称查找 handler。
     */
    private Plugin findHandler(String shortName) {
        if (apm == null) {
            return null;
        }
        for (Plugin p : apm.getHandlers()) {
            if (p.name().equalsIgnoreCase(shortName + "-handler")) {
                return p;
            }
            if (p.name().equalsIgnoreCase(shortName)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 获取所有 handler 的简称列表。
     */
    private List<String> handlerNames() {
        List<String> names = new ArrayList<>();
        if (apm != null) {
            for (Plugin p : apm.getHandlers()) {
                names.add(p.name().replace("-handler", ""));
            }
        }
        return names;
    }

    /**
     * 截断字符串到指定长度。
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + ".";
    }
}
