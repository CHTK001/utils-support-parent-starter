package com.chua.oshi.support.cli.command;

import com.beust.jcommander.Parameter;

/**
 * CLI 命令基类。
 * <p>
 * 每个子命令实现 {@link #execute()}；{@code --help/-h} 由调用方统一处理。
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractCommand {

    /** 命令描述（用于 help 列表） */
    public abstract String name();

    /** 命令一句话描述 */
    public abstract String description();

    /**
     * 执行命令。
     */
    public abstract void execute();

    /** help 标志 */
    @Parameter(names = {"--help", "-h"}, help = true, description = "Show help")
    public boolean help;

    /**
     * 打印命令帮助。
     */
    protected void printHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("Usage: oshc ").append(name()).append(" [options]\n\n");
        sb.append("Description: ").append(description()).append('\n');
        System.out.println(sb);
    }
}