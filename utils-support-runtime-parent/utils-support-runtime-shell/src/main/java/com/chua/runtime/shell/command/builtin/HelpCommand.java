package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.command.CommandRegistry;
import com.chua.runtime.shell.output.Console;

import java.util.Map;

/**
 * 帮助命令 — 列出所有可用命令。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HelpCommand implements Command {

    /**
     * 命令注册表
     */
    private final CommandRegistry registry;

    /**
     * 创建帮助命令。
     *
     * @param registry 命令注册表
     */
    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    /** 名称 */
    public String name() {
        return "help";
    }

    @Override
    /** Aliases */
    public String[] aliases() {
        return new String[]{"?", "commands"};
    }

    @Override
    /** Description */
    public String description() {
        return "显示所有可用命令";
    }

    @Override
    /** 执行 */
    public int execute(String[] args, Console console) {
        if (args.length > 0) {
            String target = args[0];
            Command cmd = registry.find(target);
            if (cmd != null) {
                console.header("命令: " + cmd.name());
                console.println("说明: " + cmd.description());
                console.println("用法: " + cmd.usage());
                return 0;
            }
            console.error("未知命令: " + target);
            return 1;
        }
        console.header("可用命令 (" + registry.size() + ")");
        console.println("命令       | 说明");
        console.println("-----------|---------");
        for (Map.Entry<String, Command> entry : registry.all().entrySet()) {
            console.println(String.format("%-12s | %s", entry.getKey(), entry.getValue().description()));
        }
        return 0;
    }
}