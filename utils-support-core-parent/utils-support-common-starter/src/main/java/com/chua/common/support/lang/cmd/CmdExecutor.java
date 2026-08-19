package com.chua.common.support.lang.cmd;

import java.util.concurrent.TimeUnit;

/**
 * 命令执行器 SPI 接口。
 *
 * <p>定义命令执行的标准行为，支持同步执行、带超时执行和异步执行。
 * 不同的实现可以通过 SPI 机制注册，使用 {@link com.chua.common.support.spi.ServiceProvider} 发现。
 *
 * <p>SPI 扩展点：实现类需标注 {@code @Spi("name")} 注解，例如：
 * <pre>{@code
 * &#64;Spi("process")
 * public class ProcessCmdExecutor implements CmdExecutor { ... }
 * }</pre>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 通过 SPI 获取执行器
 * CmdExecutor executor = ServiceProvider.of(CmdExecutor.class)
 *         .getExtension("process");
 *
 * // 同步执行
 * CmdResult result = executor.execute("ls -la");
 *
 * // 带超时执行
 * CmdResult result = executor.execute("sleep 10", 5, TimeUnit.SECONDS);
 *
 * // 异步执行
 * executor.executeAsync("ping 127.0.0.1", new CmdCallback() {
 *     public void onComplete(CmdResult r) { ... }
 * });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/15
 */
public interface CmdExecutor extends AutoCloseable {

    /**
     * 获取执行器名称
     *
     * @return 执行器名称
     */
    String getName();

    /**
     * 同步执行命令，等待执行完成后返回结果。
     *
     * @param command 要执行的命令字符串
     * @return 命令执行结果
     */
    CmdResult execute(String command);

    /**
     * 同步执行命令，指定超时时间。
     *
     * <p>如果命令在指定时间内未执行完成，将强制终止进程并返回超时结果。
     *
     * @param command 要执行的命令字符串
     * @param timeout 超时时间值
     * @param unit    超时时间单位
     * @return 命令执行结果（可能标记为超时）
     */
    CmdResult execute(String command, long timeout, TimeUnit unit);

    /**
     * 异步执行命令，通过回调接收结果。
     *
     * <p>该方法会立即返回，命令在后台执行。执行完成后通过 {@link CmdCallback} 通知结果。
     *
     * @param command  要执行的命令字符串
     * @param callback 结果回调
     */
    void executeAsync(String command, CmdCallback callback);

    /**
     * 异步执行命令（带超时），通过回调接收结果。
     *
     * @param command  要执行的命令字符串
     * @param timeout  超时时间值
     * @param unit     超时时间单位
     * @param callback 结果回调
     */
    void executeAsync(String command, long timeout, TimeUnit unit, CmdCallback callback);

    /**
     * 释放执行器占用的资源。
     *
     * <p>实现类应在此方法中关闭线程池、清理临时文件等。
     */
    @Override
    default void close() throws Exception {
        // 默认无操作，子类按需重写
    }

    /**
     * 同步执行命令并通过回调逐行输出
     *
     * <p>内部以异步方式启动，通过 {@link LineCallback} 实时接收输出行，
     * 最终阻塞等待执行完成并返回完整结果。</p>
     *
     * @param command  要执行的命令字符串
     * @param callback 逐行输出回调
     * @return 命令执行结果
     */
    default CmdResult executeWithOutput(String command, LineCallback callback) {
        return executeWithOutput(command, 0, null, callback);
    }

    /**
     * 同步执行命令（带超时）并通过回调逐行输出
     *
     * @param command  要执行的命令字符串
     * @param timeout  超时时间值
     * @param unit     超时时间单位
     * @param callback 逐行输出回调
     * @return 命令执行结果
     */
    default CmdResult executeWithOutput(String command, long timeout, TimeUnit unit, LineCallback callback) {
        CmdResult[] result = new CmdResult[1];
        Object lock = new Object();
        executeAsync(command, timeout, unit, new CmdCallback() {
            @Override
            /** On开始 */
            public void onStart(String cmd) {
                callback.onLine("[start] " + cmd);
            }
            @Override
            /** OnComplete */
            public void onComplete(CmdResult r) {
                result[0] = r;
                callback.onComplete(r.getExitCode());
                synchronized (lock) { lock.notifyAll(); }
            }
            @Override
            /** On记录错误 */
            public void onError(String cmd, Throwable t) {
                callback.onError(cmd, t);
                synchronized (lock) { lock.notifyAll(); }
            }
            @Override
            /** OnTimeout */
            public void onTimeout(String cmd, long t, TimeUnit u) {
                callback.onLine("[timeout] " + cmd);
                synchronized (lock) { lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            try { lock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return result[0];
    }
}
