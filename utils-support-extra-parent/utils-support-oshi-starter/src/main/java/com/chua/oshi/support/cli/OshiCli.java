package com.chua.oshi.support.cli;

import com.chua.common.support.utils.CommandLine;
import com.chua.oshi.support.cli.command.*;

/**
 * oshc — OSHI CLI 工具入口。
 * <p>
 * 用法:
 * <pre>
 *   oshc status   — 全局仪表盘
 *   oshc cpu      — CPU 明细
 *   oshc mem      — 内存明细
 *   oshc disk     — 磁盘明细
 *   oshc net      — 网络接口
 *   oshc sys      — 系统信息
 *   oshc help     — 帮助
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OshiCli {

    /**
     * oshicli。
     */
    private OshiCli() {
    }

    /**
     * 程序名
    */
    private static final String PROGRAM = "oshc";

    /**
     * 版本
    */
    private static final String VERSION = "1.0.0";

    /**
     * 命令注册表
    */
    private static final AbstractCommand[] COMMANDS = {
            new StatusCommand(),
            new CpuCommand(),
            new MemCommand(),
            new DiskCommand(),
            new NetworkCommand(),
            new SysCommand()
    };

    /**
     * 主入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            COMMANDS[0].execute(CommandLine.parse(new String[0]));
            return;
        }

        String command = args[0];

        if ("version".equals(command)) {
            System.out.println(PROGRAM + " " + VERSION);
            return;
        }

        if ("help".equals(command)) {
            printUsage();
            return;
        }

        for (AbstractCommand cmd : COMMANDS) {
            if (command.equals(cmd.name())) {
                // 去掉第一个命令名，用剩余参数重新解析
                String[] rest = new String[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                cmd.execute(CommandLine.parse(rest));
                return;
            }
        }

        System.err.println("Unknown command: " + command);
        System.err.println("Run '" + PROGRAM + " help' for usage.");
        System.exit(1);
    }

    /**
     * printusage。
     */
    private static void printUsage() {
        System.out.println(PROGRAM + " — OSHI CLI dashboard (v" + VERSION + ")\n");
        System.out.printf("  %-10s  %s%n", "command", "description");
        System.out.println("  " + "─".repeat(60));
        for (AbstractCommand cmd : COMMANDS) {
            System.out.printf("  %-10s  %s%n", cmd.name(), cmd.description());
        }
        System.out.println();
        System.out.println("Global flags:");
        System.out.println("  --help     Show help");
        System.out.println("  --up       (net only) show UP interfaces");
        System.out.println("  --services (sys only) list running services");
        System.out.println();
        System.out.println("Example: oshc status, oshc cpu, oshc net --up");
    }
}
