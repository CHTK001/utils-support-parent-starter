package com.chua.common.support.task.script.process;

import com.chua.common.support.spi.annotations.Spi;

/**
 * PowerShell 脚本引擎：调用本机 powershell / pwsh 执行脚本文件。
 *
 * <p>SPI 注册名 {@code powershell}，同时登记 {@code pwsh}（PowerShell 7 的命令名）
 * 作为别名。</p>
 *
 * <p>执行固定带 {@code -NoProfile -ExecutionPolicy Bypass -File}：不加
 * {@code ExecutionPolicy Bypass} 时，默认策略为 Restricted 的机器会直接拒绝运行脚本；
 * 不加 {@code -NoProfile} 则会把运维的个人配置带进任务环境。</p>
 *
 * <p>保存期不做语法检查（{@link #checkArgs} 返回 null）：PowerShell 没有
 * {@code -n}/{@code -l} 这类轻量开关，用 {@code [Parser]::ParseFile} 需要拼一段
 * 带路径引号的内联命令，路径含空格时容易误判；宁可把问题留到执行期给出可读失败，
 * 也不制造「保存时莫名其妙报错」。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Spi({"powershell", "pwsh"})
public class PowerShellScriptProvider extends ProcessScriptProvider {

    /**
     * 引擎名
     */
    private static final String ENGINE_NAME = "powershell";

    /**
     * 获取引擎名称。
     *
     * @return 引擎名 {@code powershell}
     */
    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    /**
     * 解释器候选：Windows 自带 {@code powershell}，PowerShell 7 为 {@code pwsh}。
     *
     * @return 候选命令
     */
    @Override
    protected String[] interpreterCandidates() {
        return new String[]{"powershell", "pwsh", "powershell.exe"};
    }

    /**
     * 探测参数：{@code --version} 不被 Windows PowerShell 支持，改取版本表。
     *
     * @return 探测参数
     */
    @Override
    protected String[] probeArgs() {
        return new String[]{"-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"};
    }

    /**
     * 脚本正文之前的固定参数。
     *
     * @return 前置参数
     */
    @Override
    protected String[] preArgs() {
        return new String[]{"-NoProfile", "-ExecutionPolicy", "Bypass", "-File"};
    }
}
