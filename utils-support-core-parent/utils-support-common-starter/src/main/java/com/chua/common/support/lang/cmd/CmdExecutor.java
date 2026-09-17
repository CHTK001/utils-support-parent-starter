package com.chua.common.support.lang.cmd;

import java.io.File;
import java.util.Map;
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

    // ==================== 数组形式执行 ====================

    /**
    * 同步执行数组形式的命令。
    *
    * <p>数组形式直接对应 {@link ProcessBuilder} 的参数列表，{@code command[0]} 为可执行程序，
    * 其余为参数。参数不经 Shell 解析、无需引号转义，因此天然免疫命令注入，
    * 也不会出现空格、引号在 Windows 与 Unix 上的语义差异。</p>
    *
    * <p><strong>凡是程序生成的参数都应使用数组形式</strong>，而非拼成字符串后调用
    * {@link #execute(String)}——后者需要执行器反向解析，属于有损往返。</p>
    *
    * <p>默认实现将数组以空格拼接后委托给 {@link #execute(String)}，
    * 未覆写的实现类行为与字符串执行保持一致。</p>
    *
    * @param command 程序名与参数数组
    * @return 命令执行结果
    */
    default CmdResult execute(String[] command) {
        return execute(joinCommand(command));
    }

    /**
    * 同步执行数组形式的命令，指定超时时间。
    *
    * @param command 程序名与参数数组
    * @param timeout 超时时间值
    * @param unit    超时时间单位
    * @return 命令执行结果（可能标记为超时）
    */
    default CmdResult execute(String[] command, long timeout, TimeUnit unit) {
        return execute(joinCommand(command), timeout, unit);
    }

    /**
    * 异步执行数组形式的命令，通过回调接收结果。
    *
    * @param command  程序名与参数数组
    * @param callback 结果回调
    */
    default void executeAsync(String[] command, CmdCallback callback) {
        executeAsync(joinCommand(command), callback);
    }

    /**
    * 异步执行数组形式的命令（带超时），通过回调接收结果。
    *
    * @param command  程序名与参数数组
    * @param timeout  超时时间值
    * @param unit     超时时间单位
    * @param callback 结果回调
    */
    default void executeAsync(String[] command, long timeout, TimeUnit unit, CmdCallback callback) {
        executeAsync(joinCommand(command), timeout, unit, callback);
    }

    /**
    * 同步执行数组形式的命令并逐行接收输出。
    *
    * @param command  程序名与参数数组
    * @param callback 逐行输出回调
    * @return 命令执行结果
    */
    default CmdResult executeWithOutput(String[] command, LineCallback callback) {
        return executeWithOutput(joinCommand(command), 0, null, callback);
    }

    /**
    * 同步执行数组形式的命令（带超时）并逐行接收输出。
    *
    * @param command  程序名与参数数组
    * @param timeout  超时时间值
    * @param unit     超时时间单位
    * @param callback 逐行输出回调
    * @return 命令执行结果
    */
    default CmdResult executeWithOutput(String[] command, long timeout, TimeUnit unit, LineCallback callback) {
        return executeWithOutput(joinCommand(command), timeout, unit, callback);
    }

    // ==================== 扩展参数执行（工作目录/环境变量/标准输入） ====================

    /**
    * 同步执行数组形式的命令，支持工作目录、环境变量与标准输入。
    *
    * <p>默认实现忽略扩展参数并回退到 {@link #execute(String[], long, TimeUnit)}；
    * 需要完整能力的实现类（如 {@code ProcessCmdExecutor}）应覆写此方法。</p>
    *
    * @param command          程序名与参数数组
    * @param timeout          超时值（≤0 表示不超时）
    * @param unit             超时单位
    * @param workingDirectory 工作目录，可为 null 表示使用当前目录
    * @param environment      附加环境变量，可为 null
    * @param input            标准输入内容，可为 null
    * @return 命令执行结果
    */
    default CmdResult execute(String[] command, long timeout, TimeUnit unit,
                              File workingDirectory, Map<String, String> environment, String input) {
        return execute(command, timeout, unit);
    }

    /**
    * 同步执行数组形式的命令并逐行接收输出，支持工作目录、环境变量与标准输入。
    *
    * <p>默认实现忽略扩展参数并回退到
    * {@link #executeWithOutput(String[], long, TimeUnit, LineCallback)}。</p>
    *
    * @param command          程序名与参数数组
    * @param timeout          超时值（≤0 表示不超时）
    * @param unit             超时单位
    * @param callback         逐行输出回调
    * @param workingDirectory 工作目录，可为 null
    * @param environment      附加环境变量，可为 null
    * @param input            标准输入内容，可为 null
    * @return 命令执行结果
    */
    default CmdResult executeWithOutput(String[] command, long timeout, TimeUnit unit, LineCallback callback,
                                        File workingDirectory, Map<String, String> environment, String input) {
        return executeWithOutput(command, timeout, unit, callback);
    }

    /**
    * 异步执行数组形式的命令，支持工作目录、环境变量与标准输入。
    *
    * <p>默认实现忽略扩展参数并回退到
    * {@link #executeAsync(String[], long, TimeUnit, CmdCallback)}。</p>
    *
    * @param command          程序名与参数数组
    * @param timeout          超时值（≤0 表示不超时）
    * @param unit             超时单位
    * @param callback         结果回调
    * @param workingDirectory 工作目录，可为 null
    * @param environment      附加环境变量，可为 null
    * @param input            标准输入内容，可为 null
    */
    default void executeAsync(String[] command, long timeout, TimeUnit unit, CmdCallback callback,
                              File workingDirectory, Map<String, String> environment, String input) {
        executeAsync(command, timeout, unit, callback);
    }

    /**
    * 将参数数组拼接为命令字符串，供未覆写数组方法的实现类兜底。
    *
    * <p>空数组返回空字符串，交由实现类的空命令校验逻辑处理；
    * 数组中的 null 元素会被跳过，避免拼接时抛出空指针异常。</p>
    *
    * @param command 参数数组
    * @return 拼接后的命令字符串
    */
    private static String joinCommand(String[] command) {
        if (command == null || command.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (arg == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(arg);
        }
        return sb.toString();
    }

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
