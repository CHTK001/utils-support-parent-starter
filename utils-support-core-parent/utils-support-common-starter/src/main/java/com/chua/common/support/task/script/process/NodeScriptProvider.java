package com.chua.common.support.task.script.process;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Node.js 脚本引擎：调用本机 node 执行脚本文件。
 *
 * <p>SPI 注册名 {@code node}，同时登记 {@code nodejs}——调度侧
 * {@code GLUE_NODEJS} 归一后的引擎名是后者，两个名字都要能取到实例。</p>
 *
 * <p>保存期校验用 {@code node --check}，只对 CommonJS 语法有效；ESM（{@code .mjs}）
 * 写法可能被误判，遇到该形态时以执行期结果为准。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
@Spi({"node", "nodejs"})
public class NodeScriptProvider extends ProcessScriptProvider {

    /**
     * 引擎名
     */
    private static final String ENGINE_NAME = "node";

    /**
     * 获取引擎名称。
     *
     * @return 引擎名 {@code node}
     */
    @Override
    public String engineName() {
        return ENGINE_NAME;
    }

    /**
     * 解释器候选：Windows 上命令名可能带 {@code .exe} 后缀。
     *
     * @return 候选命令
     */
    @Override
    protected String[] interpreterCandidates() {
        return new String[]{"node", "node.exe"};
    }

    /**
     * 语法检查参数：{@code node --check <文件>}。
     *
     * @param scriptPath 脚本路径
     * @return 检查参数
     */
    @Override
    protected String[] checkArgs(Path scriptPath) {
        return new String[]{"--check"};
    }
}
