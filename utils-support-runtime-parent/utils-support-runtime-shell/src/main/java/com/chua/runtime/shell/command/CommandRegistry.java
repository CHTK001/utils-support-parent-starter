package com.chua.runtime.shell.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令注册表 — 管理命令名称与实例映射。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CommandRegistry {

    /**
     * 命令映射
     */
    private final Map<String, Command> commands;

    /**
     * 别名映射
     */
    private final Map<String, Command> aliases;

    /**
     * 创建命令注册表。
     */
    public CommandRegistry() {
        this.commands = new LinkedHashMap<>();
        this.aliases = new LinkedHashMap<>();
    }

    /**
     * 注册命令。
     *
     * @param command 命令
     */
    public void register(Command command) {
        commands.put(command.name().toLowerCase(), command);
        for (String alias : command.aliases()) {
            aliases.put(alias.toLowerCase(), command);
        }
    }

    /**
     * 按名称或别名查找命令。
     *
     * @param name 命令名称
     * @return 命令实例，不存在返回 null
     */
    public Command find(String name) {
        String key = name.toLowerCase();
        Command cmd = commands.get(key);
        if (cmd != null) {
            return cmd;
        }
        return aliases.get(key);
    }

    /**
     * 所有已注册命令。
     *
     * @return 命令映射
     */
    public Map<String, Command> all() {
        return java.util.Collections.unmodifiableMap(commands);
    }

    /**
     * 命令数量。
     *
     * @return 数量
     */
    public int size() {
        return commands.size();
    }
}