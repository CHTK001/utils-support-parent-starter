package com.chua.common.support.task.script.process;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * PHP 脚本引擎：调用本机 php 解释器执行脚本文件。
 *
 * <p>SPI 注册名 {@code php}，对应调度侧 {@code GLUE_PHP} 归一后的引擎名。</p>
 *
 * <p>保存期校验用 {@code php -l}（只做语法检查）。注意 {@code -l} 对含语法错误以外的
 * 运行期问题不报错，这与其它引擎「只挡语法」的口径一致。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Spi("php")
public class PhpScriptProvider extends ProcessScriptProvider {

    /**
     * 引擎名
     */
    private static final String ENGINE_NAME = "php";

    /**
     * 获取引擎名称。
     *
     * @return 引擎名 {@code php}
     */
    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    /**
     * 解释器候选。
     *
     * @return 候选命令
     */
    @Override
    protected String[] interpreterCandidates() {
        return new String[]{"php"};
    }

    /**
     * 语法检查参数：{@code php -l <文件>}。
     *
     * @param scriptPath 脚本路径
     * @return 检查参数
     */
    @Override
    protected String[] checkArgs(Path scriptPath) {
        return new String[]{"-l"};
    }
}
