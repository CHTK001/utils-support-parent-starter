package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.apm.ApmBootstrap;
import com.chua.runtime.apm.handler.DependencyGraphHandler;
import com.chua.runtime.apm.handler.FileHandler;
import com.chua.runtime.apm.handler.HandleLeakHandler;
import com.chua.runtime.apm.handler.LogEntry;
import com.chua.runtime.apm.handler.LogHandler;
import com.chua.runtime.apm.handler.NetHandler;
import com.chua.runtime.apm.handler.TraceHandler;
import com.chua.runtime.apm.handler.TransmissionHandler;
import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.protocol.DependencyEdge;
import com.chua.runtime.protocol.TransmissionRecord;
import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.util.ArrayList;
import java.util.List;

/**
 * APM 命令 — 动态展示已加载的 APM 处理器及其数据。
 *
 * <p>子命令从 ApmBootstrap 实际加载的 Plugin 动态生成：
 * 主命令 {@code apm} 显示总览，{@code apm &lt;handler&gt;} 查看具体 处理器 数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ApmCommand implements Command {

    /**
     * 处理器 名称中的 "-处理器" 后缀
     */
    private static final String HANDLER_SUFFIX = "-handler";

    /**
     * 主命令名
     */
    private static final String CMD_NAME = "apm";

    /**
     * "列表" 子命令
     */
    private static final String SUB_LIST = "list";

    /**
     * "状态" 子命令
     */
    private static final String SUB_STATUS = "status";

    /**
     * "help" 子命令
     */
    private static final String SUB_HELP = "help";

    /**
     * "日志" 子命令（向后兼容别名）
     */
    private static final String SUB_LOGS = "logs";

    /**
     * "net" 子命令（向后兼容别名）
     */
    private static final String SUB_NET = "net";

    /**
     * "文件" 子命令（向后兼容别名）
     */
    private static final String SUB_FILE = "file";

    /**
     * "追踪" 子命令（向后兼容别名）
     */
    private static final String SUB_TRACE = "trace";

    /**
     * 处理器 运行状态字符串
     */
    private static final String STATUS_RUNNING = "RUNNING";

    /**
     * 处理器 停止状态字符串
     */
    private static final String STATUS_STOPPED = "STOPPED";

    /**
     * 根 Span 标识（父spanid 为 空 时显示）
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
    /** 名称 */
    public String name() {
        return CMD_NAME;
    }

    @Override
    /** Aliases */
    public String[] aliases() {
 // 动态别名：处理器 名称去除 -处理器 后缀
        List<String> aliases = new ArrayList<>();
        if (apm != null) {
            for (Plugin p : apm.getHandlers()) {
                aliases.add(shortName(p.name()));
            }
        }
        return aliases.toArray(new String[0]);
    }

    @Override
    /** Description */
    public String description() {
        return "查看 APM 拦截数据 (apm [handler])";
    }

    @Override
    /** 完成 */
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
    /** 执行 */
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
        console.println("提示: 输入 'apm " + handlerNames().getFirst() + "' 查看详细数据");
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
     * 显示具体 处理器 的数据。
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
 // instanceof 路由不可迁移至 服务提供者：上述 7 个 处理器 均为具体实现类（非可扩展 SPI 接口），
 // 各自持有不同的数据结构（日志entry/netrecord/文件record/Span/transmissionrecord/dependencyedge/处理record），
        // 分发逻辑为业务固定的类型路由，故保留。
        if (handler instanceof LogHandler) {
            showLogs((LogHandler) handler, console, limit);
        } else if (handler instanceof NetHandler) {
            showNet((NetHandler) handler, console, limit);
        } else if (handler instanceof FileHandler) {
            showFile((FileHandler) handler, console, limit);
        } else if (handler instanceof TraceHandler) {
            showTrace((TraceHandler) handler, console, limit);
        } else if (handler instanceof TransmissionHandler) {
            showTransmission((TransmissionHandler) handler, console, limit);
        } else if (handler instanceof DependencyGraphHandler) {
            showDependency((DependencyGraphHandler) handler, console, limit);
        } else if (handler instanceof HandleLeakHandler) {
            showLeak((HandleLeakHandler) handler, console, limit);
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
     * 显示传输链路记录。
     *
     * @param handler 传输处理器
     * @param console 控制台
     * @param limit   显示条数
     */
    private void showTransmission(TransmissionHandler handler, Console console, int limit) {
        List<TransmissionRecord> records = handler.getRecords();
        console.header("传输记录 (" + records.size() + ", 最近 " + limit + ")");
        console.println("协议    | 软件栈       | 操作             | 目标地址            | 耗时(ms)");
        console.println("--------|--------------|------------------|----------------------|----------");
        int start = Math.max(0, records.size() - limit);
        for (int i = start; i < records.size(); i++) {
            TransmissionRecord r = records.get(i);
            console.println(String.format("%-8s | %-12s | %-16s | %-22s | %d",
                    r.getProtocol() != null ? r.getProtocol().name() : "-",
                    r.getSoftware() != null ? truncate(r.getSoftware().name(), 12) : "-",
                    truncate(r.getOperation(), 16),
                    truncate(r.getTarget() != null ? r.getTarget().displayLabel() : "?", 22),
                    r.getDuration()));
        }
        if (records.isEmpty()) {
            console.println("  （暂无传输记录）");
        }
    }

    /**
     * 显示依赖图。
     *
     * @param handler 依赖图处理器
     * @param console 控制台
     * @param limit   显示条数
     */
    private void showDependency(DependencyGraphHandler handler, Console console, int limit) {
        List<DependencyEdge> edges = handler.getEdges();
        console.header("依赖图 (" + edges.size() + ", 最近 " + limit + ")");
        console.println("源节点                  -> 目标节点                | 协议   | 软件栈       | 调用次数");
        console.println("-----------------------  ------------------------  |--------|--------------|--------");
        int start = Math.max(0, edges.size() - limit);
        for (int i = start; i < edges.size(); i++) {
            DependencyEdge e = edges.get(i);
            console.println(String.format("%-23s -> %-24s | %-8s | %-12s | %d",
                    truncate(e.getSource() != null ? e.getSource().displayLabel() : "?", 23),
                    truncate(e.getTarget() != null ? e.getTarget().displayLabel() : "?", 24),
                    e.getProtocol() != null ? e.getProtocol().name() : "-",
                    e.getSoftware() != null ? truncate(e.getSoftware().name(), 12) : "-",
                    e.getCallCount()));
        }
        if (edges.isEmpty()) {
            console.println("  （暂无依赖边）");
        }
    }

    /**
     * 显示句柄泄漏检测。
     *
     * @param handler 句柄泄漏处理器
     * @param console 控制台
     * @param limit   显示条数
     */
    private void showLeak(HandleLeakHandler handler, Console console, int limit) {
        List<HandleLeakHandler.HandleRecord> leaks = handler.detectLeaks();
        console.header("句柄泄漏检测 (" + leaks.size() + " 个泄漏, 活跃 " + handler.getHandles().size() + ")");
        console.println("类型      | 句柄名                 | 线程                     | 存活(ms)");
        console.println("----------|------------------------|--------------------------|----------");
        int start = Math.max(0, leaks.size() - limit);
        for (int i = start; i < leaks.size(); i++) {
            HandleLeakHandler.HandleRecord r = leaks.get(i);
            console.println(String.format("%-10s | %-22s | %-26s | %d",
                    r.getKind() != null ? r.getKind().name() : "?",
                    truncate(r.getName(), 22),
                    truncate(r.getOwnerThread(), 26),
                    r.age()));
        }
        if (leaks.isEmpty()) {
            console.println("  （无泄漏句柄）");
        }
    }

    /**
     * 根据简称查找 处理器。
     *
     * @param shortName 简称
     * @return 处理器实例，未找到返回 空
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
     * 获取所有 处理器 的简称列表。
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
     * 去除 处理器 名称中的 "-处理器" 后缀得到简称。
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
