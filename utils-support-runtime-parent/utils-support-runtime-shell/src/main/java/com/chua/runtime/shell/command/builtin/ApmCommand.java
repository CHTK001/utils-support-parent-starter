package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.LogEntry;
import com.chua.runtime.apm.handler.LogHandler;
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
 * 主命令 {@code apm} 显示总览，{@code apm &lt;handler&gt;} 查看具体 handler 数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ApmCommand implements Command {

    /**
     * Handler 名称中的 "-handler" 后缀
     */
    private static final String HANDLER_SUFFIX = "-handler";

    /**
     * 主命令名
     */
    private static final String CMD_NAME = "apm";

    /**
     * "list" 子命令
     */
    private static final String SUB_LIST = "list";

    /**
     * "status" 子命令
     */
    private static final String SUB_STATUS = "status";

    /**
     * "help" 子命令
     */
    private static final String SUB_HELP = "help";

    /**
     * "logs" 子命令（向后兼容别名）
     */
    private static final String SUB_LOGS = "logs";

    /**
     * "net" 子命令（向后兼容别名）
     */
    private static final String SUB_NET = "net";

    /**
     * "file" 子命令（向后兼容别名）
     */
    private static final String SUB_FILE = "file";

    /**
     * "trace" 子命令（向后兼容别名）
     */
    private static final String SUB_TRACE = "trace";

    /**
     * Handler 运行状态字符串
     */
    private static final String STATUS_RUNNING = "RUNNING";

    /**
     * Handler 停止状态字符串
     */
    private static final String STATUS_STOPPED = "STOPPED";

    /**
     * 根 Span 标识（parentSpanId 为 null 时显示）
     */
    private static final String ROOT_SPAN = "(root)";

    /**
     * 默认日志/记录显示条数
     */
    private static final int DEFAULT_DISPLAY_LIMIT = 20;

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
        return CMD_NAME;
    }

    @Override
    public String[] aliases() {
        // 动态别名：handler 名称去除 -handler 后缀
        List<String> aliases = new ArrayList<>();
        if (apm != null) {
            for (Plugin p : apm.getHandlers()) {
                aliases.add(shortName(p.name()));
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
            completions.add(SUB_LIST);
            completions.add(SUB_STATUS);
            completions.add(SUB_HELP);
            if (apm != null) {
                for (Plugin p : apm.getHandlers()) {
                    String name = shortName(p.name());
                    if (name.equals(args.length == 0 ? "" : args[0])) {
                        continue;
                    }
                    completions.add(name);
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
            case SUB_LIST:
                showOverview(console);
                break;
            case SUB_STATUS:
                console.println(apm.status());
                break;
            case SUB_HELP:
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
                    p.isRunning() ? STATUS_RUNNING : STATUS_STOPPED,
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
        int limit = DEFAULT_DISPLAY_LIMIT;
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
     *
     * @param handler 日志处理器
     * @param console 控制台
     * @param limit   显示条数
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
     *
     * @param handler 网络处理器
     * @param console 控制台
     * @param limit   显示条数
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
     *
     * @param handler 文件处理器
     * @param console 控制台
     * @param limit   显示条数
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
     *
     * @param handler 追踪处理器
     * @param console 控制台
     * @param limit   显示条数
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
                    s.getParentSpanId() != null ? truncate(s.getParentSpanId(), 17) : ROOT_SPAN,
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
     *
     * @param shortName 简称
     * @return 处理器实例，未找到返回 null
     */
    private Plugin findHandler(String shortName) {
        if (apm == null) {
            return null;
        }
        for (Plugin p : apm.getHandlers()) {
            if (p.name().equalsIgnoreCase(shortName + HANDLER_SUFFIX)) {
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
     *
     * @return 简称列表
     */
    private List<String> handlerNames() {
        List<String> names = new ArrayList<>();
        if (apm != null) {
            for (Plugin p : apm.getHandlers()) {
                names.add(shortName(p.name()));
            }
        }
        return names;
    }

    /**
     * 去除 handler 名称中的 "-handler" 后缀得到简称。
     *
     * @param fullName 完整名称
     * @return 简称
     */
    private String shortName(String fullName) {
        return fullName.replace(HANDLER_SUFFIX, "");
    }

    /**
     * 截断字符串到指定长度（省略号补齐）。
     *
     * @param s   原始字符串
     * @param max 最大长度
     * @return 截断结果
     */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + ".";
    }
}
