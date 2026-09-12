package com.chua.runtime.shell.command;

import com.chua.runtime.shell.output.Console;

import java.util.Collections;
import java.util.List;

/**
* Shell 命令接口 — 所有内置/自定义命令必须实现。
*
* @author CH
* @since 4.0.0.42
 */
public interface Command {

    /**
    * 命令名称。
    *
    * @return 命令名称
     */
    String name();

    /**
    * 命令别名。
    *
    * @return 别名数组
     */
    default String[] aliases() {
        return new String[0];
    }

    /**
    * 命令说明。
    *
    * @return 说明文本
     */
    String description();

    /**
    * 用法示例。
    *
    * @return 用法文本
     */
    default String usage() {
        return name();
    }

    /**
    * Tab 补全建议。
    *
    * <p>ShellSession 在用户按下 Tab 时调用此方法获取当前参数上下文下的补全候选。</p>
    *
    * @param args 当前已输入的参数数组（不含命令名本身）
    * @return 补全候选列表
     */
    default List<String> complete(String[] args) {
        return Collections.emptyList();
    }

    /**
    * 执行命令。
    *
    * @param args    参数数组
    * @param console 控制台输出
    * @return 执行结果代码
     */
    int execute(String[] args, Console console);
}