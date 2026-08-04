package com.chua.common.support.lang.cmd;

import org.jspecify.annotations.NullUnmarked;

/**
 * 命令行实时输出回调接口。
 *
 * <p>用于在命令执行过程中逐行接收输出内容，适用于安装进度、日志跟踪等场景。</p>
 *
 * @author CH
 */
@NullUnmarked
public interface LineCallback {

    /**
     * 接收到一行输出时回调
     *
     * @param line 输出的文本行
     */
    default void onLine(String line) {}

    /**
     * 命令执行完成时回调
     *
     * @param exitCode 退出码
     */
    default void onComplete(int exitCode) {}

    /**
     * 命令执行异常时回调
     *
     * @param command 执行的命令
     * @param throwable 异常信息
     */
    default void onError(String command, Throwable throwable) {}
}
