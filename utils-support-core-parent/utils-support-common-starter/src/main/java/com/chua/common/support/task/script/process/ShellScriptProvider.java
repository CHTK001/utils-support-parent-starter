package com.chua.common.support.task.script.process;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Shell 脚本引擎：调用本机 bash / sh 执行脚本文件。
 *
 * <p>SPI 注册名：{@code shell}（调度侧 {@code GLUE_SHELL} 归一后的引擎名），
 * 同时登记 {@code bash} / {@code sh} 两个别名，便于直接按解释器名取用。</p>
 *
 * <p>探测用 {@code -c 'exit 0'} 而不是 {@code --version}：dash 等 POSIX sh 不认
 * {@code --version}，用版本开关会把本来可用的解释器误判为缺失。</p>
 *
 * <p>保存期校验用 {@code -n}（只做语法解析，不执行）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Spi({"shell", "bash", "sh"})
public class ShellScriptProvider extends ProcessScriptProvider {

    /**
     * 引擎名
     */
    private static final String ENGINE_NAME = "shell";

    /**
     * 获取引擎名称。
     *
     * @return 引擎名 {@code shell}
     */
    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    /**
     * 解释器候选：Windows 上通常来自 Git Bash 的 {@code bash}，Linux 两者都有。
     *
     * @return 候选命令
     */
    @Override
    protected String[] interpreterCandidates() {
        return new String[]{"bash", "sh"};
    }

    /**
     * 探测参数：用一次空执行判断可用性。
     *
     * @return 探测参数
     */
    @Override
    protected String[] probeArgs() {
        return new String[]{"-c", "exit 0"};
    }

    /**
     * 语法检查参数：{@code bash -n <文件>}。
     *
     * @param scriptPath 脚本路径
     * @return 检查参数
     */
    @Override
    protected String[] checkArgs(Path scriptPath) {
        return new String[]{"-n"};
    }
}
