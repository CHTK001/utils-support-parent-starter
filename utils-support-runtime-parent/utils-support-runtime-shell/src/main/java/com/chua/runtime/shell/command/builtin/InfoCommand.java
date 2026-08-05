package com.chua.runtime.shell.command.builtin;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.output.Console;

import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Properties;

/**
 * 环境信息命令 — 显示系统与 JVM 属性。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InfoCommand implements Command {

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String[] aliases() {
        return new String[]{"env", "version"};
    }

    @Override
    public String description() {
        return "显示 JVM 与环境信息";
    }

    @Override
    public int execute(String[] args, Console console) {
        Properties props = System.getProperties();
        console.header("环境信息");
        console.println("Java 版本   : " + props.getProperty("java.version"));
        console.println("Java 提供商 : " + props.getProperty("java.vendor"));
        console.println("JVM 名称    : " + props.getProperty("java.vm.name"));
        console.println("JVM 版本    : " + props.getProperty("java.vm.version"));
        console.println("操作系统    : " + props.getProperty("os.name") + " " + props.getProperty("os.version"));
        console.println("用户目录    : " + props.getProperty("user.dir"));
        console.println("启动时间    : " + new Date(ManagementFactory.getRuntimeMXBean().getStartTime()));
        return 0;
    }
}