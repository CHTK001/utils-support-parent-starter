package com.chua.oshi.support.cli.command;

/**
 * CLI 命令基类。
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractCommand {

    /** 命令名（用于 cli 路由与 help 列表） */
    public abstract String name();

    /** 命令一句话描述 */
    public abstract String description();

    /**
     * 执行命令。
     *
     * @param options 解析后的参数（key-value）
     */
    public abstract void execute(com.chua.common.support.utils.CommandLine options);
}