package com.chua.common.support.lang.cmd;

import java.util.concurrent.TimeUnit;

/**
* 命令执行的异步回调接口。
*
* <p>用于在异步执行命令时接收执行结果或异常通知。
* 回调方法的执行线程由 {@link CmdExecutors} 或具体的 {@link CmdExecutor} 实现控制。
*
* <p>使用示例：
* <pre>{@code
* CmdExecutors.executeAsync("ping -c 4 127.0.0.1", new CmdCallback() {
*     public void onComplete(CmdResult result) {
*         System.out.println("执行完成: " + result);
*     }
*     public void onTimeout(String command, long timeout, TimeUnit unit) {
*         System.err.println("命令超时: " + command);
*     }
* });
* }</pre>
*
* @author CH
* @since 2026/07/15
 */
public interface CmdCallback {

    /**
    * 命令开始执行时回调
    *
    * @param command 被执行的命令字符串
     */
    default void onStart(String command) {}

    /**
    * 命令执行完成时回调（包括正常完成和超时完成）
    *
    * @param result 命令执行结果
     */
    void onComplete(CmdResult result);

    /**
    * 命令因超时而终止时回调
    *
    * @param command 被执行的命令字符串
    * @param timeout 超时时间值
    * @param unit    超时时间单位
     */
    default void onTimeout(String command, long timeout, TimeUnit unit) {}

    /**
    * 命令执行过程中发生异常时回调
    *
    * @param command   被执行的命令字符串
    * @param throwable 捕获的异常
     */
    default void onError(String command, Throwable throwable) {}
}
