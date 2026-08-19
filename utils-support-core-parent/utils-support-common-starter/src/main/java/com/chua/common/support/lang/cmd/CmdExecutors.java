package com.chua.common.support.lang.cmd;

import com.chua.common.support.spi.ServiceProvider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行工具门面类，提供便捷的命令执行入口。
 *
 * <p>内部通过 SPI 机制自动发现并选择合适的 {@link CmdExecutor} 实现。
 * 默认使用 {@code "process"} 执行器，可通过 {@link #setDefaultName(String)} 切换。
 *
 * <h3>功能概览：</h3>
 * <ul>
 *   <li>{@link #execute(String)} — 同步执行命令</li>
 *   <li>{@link #execute(String, long, TimeUnit)} — 同步执行（带超时）</li>
 *   <li>{@link #executeAsync(String, CmdCallback)} — 异步执行</li>
 *   <li>{@link #executeAsync(String, long, TimeUnit, CmdCallback)} — 异步执行（带超时）</li>
 *   <li>{@link #getExecutor()} — 获取当前使用的 CmdExecutor 实例</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 同步执行
 * CmdResult result = CmdExecutors.execute("ls -la");
 * System.out.println(result.getStdout());
 *
 * // 带超时执行
 * CmdResult result = CmdExecutors.execute("ping 127.0.0.1", 3, TimeUnit.SECONDS);
 * System.out.println("超时: " + result.isTimeout());
 *
 * // 异步执行
 * CmdExecutors.executeAsync("echo Hello", new CmdCallback() {
 *     public void onComplete(CmdResult r) {
 *         System.out.println(r.getStdout());
 *     }
 * });
 * }</pre>
 *
 * @since 2026/07/15
 */
public final class CmdExecutors {

    /** 默认执行器名称 */
    private static String DEFAULT_NAME = "process";

    /** 缓存的 CmdExecutor 实例 */
    private static volatile CmdExecutor executor;

    private CmdExecutors() {}

    /**
     * 设置默认执行器名称
     *
     * @param name SPI 执行器名称
     */
    public static void setDefaultName(String name) {
        synchronized (CmdExecutors.class) {
            DEFAULT_NAME = name;
            if (executor != null) {
                try { executor.close(); } catch (Exception ignored) { }
                executor = null;
            }
        }
    }

    /**
     * 获取当前使用的 CmdExecutor 实例
     *
     * @return CmdExecutor 实例
     */
    public static CmdExecutor getExecutor() {
        if (executor == null) {
            synchronized (CmdExecutors.class) {
                if (executor == null) {
                    executor = ServiceProvider.of(CmdExecutor.class).getExtension(DEFAULT_NAME);
                    if (executor == null) {
                        // 如果 SPI 未发现扩展，使用默认实现
                        executor = new ProcessCmdExecutor();
                    }
                }
            }
        }
        return executor;
    }

    /**
     * 刷新执行器（关闭旧执行器，下一次调用时重新通过 SPI 获取）
     */
    public static void refresh() {
        synchronized (CmdExecutors.class) {
            if (executor != null) {
                try { executor.close(); } catch (Exception ignored) { }
                executor = null;
            }
        }
    }

    /**
     * 关闭当前执行器并释放资源。
     *
     * <p>应用关闭时应调用此方法以终止内部线程池。
     */
    public static void shutdown() {
        synchronized (CmdExecutors.class) {
            if (executor != null) {
                try { executor.close(); } catch (Exception ignored) { }
                executor = null;
            }
        }
    }

    // ==================== 同步执行 ====================

    /**
     * 同步执行命令
     *
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    public static CmdResult execute(String command) {
        return getExecutor().execute(command);
    }

    /**
     * 同步执行命令（带超时）
     *
     * @param command 要执行的命令
     * @param timeout 超时时间值
     * @param unit    超时时间单位
     * @return 命令执行结果
     */
    public static CmdResult execute(String command, long timeout, TimeUnit unit) {
        return getExecutor().execute(command, timeout, unit);
    }

    // ==================== 异步执行 ====================

    /**
     * 异步执行命令，通过回调接收结果
     *
     * @param command  要执行的命令
     * @param callback 结果回调
     */
    public static void executeAsync(String command, CmdCallback callback) {
        getExecutor().executeAsync(command, callback);
    }

    /**
     * 异步执行命令（带超时），通过回调接收结果
     *
     * @param command  要执行的命令
     * @param timeout  超时时间值
     * @param unit     超时时间单位
     * @param callback 结果回调
     */
    public static void executeAsync(String command, long timeout, TimeUnit unit, CmdCallback callback) {
        getExecutor().executeAsync(command, timeout, unit, callback);
    }

    // ==================== 便捷异步执行 ====================

    /**
     * 异步执行命令，返回 {@link CompletableFuture}。
     *
     * <p>适用于 Java 8+ 的函数式风格：
     * <pre>{@code
     * CmdExecutors.executeAsync("ls -la")
     *     .thenApply(CmdResult::getStdout)
     *     .thenAccept(System.out::println);
     * }</pre>
     *
     * @param command 要执行的命令
     * @return CompletableFuture 封装的结果
     */
    public static CompletableFuture<CmdResult> executeAsync(String command) {
        CompletableFuture<CmdResult> future = new CompletableFuture<>();
        executeAsync(command, new CmdCallback() {
            @Override
            public void onComplete(CmdResult result) {
                future.complete(result);
            }

            @Override
            public void onError(String cmd, Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * 异步执行命令（带超时），返回 {@link CompletableFuture}。
     *
     * @param command 要执行的命令
     * @param timeout 超时时间值
     * @param unit    超时时间单位
     * @return CompletableFuture 封装的结果
     */
    public static CompletableFuture<CmdResult> executeAsync(String command, long timeout, TimeUnit unit) {
        CompletableFuture<CmdResult> future = new CompletableFuture<>();
        executeAsync(command, timeout, unit, new CmdCallback() {
            @Override
            public void onComplete(CmdResult result) {
                future.complete(result);
            }

            @Override
            public void onError(String cmd, Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    // ==================== 实时输出执行 ====================

    /**
     * 同步执行命令并通过回调逐行接收输出
     *
     * @param command  要执行的命令
     * @param callback 逐行输出回调
     * @return 命令执行结果
     */
    public static CmdResult executeWithOutput(String command, LineCallback callback) {
        return getExecutor().executeWithOutput(command, callback);
    }

    /**
     * 同步执行命令（带超时）并通过回调逐行接收输出
     *
     * @param command  要执行的命令
     * @param timeout  超时时间值
     * @param unit     超时时间单位
     * @param callback 逐行输出回调
     * @return 命令执行结果
     */
    public static CmdResult executeWithOutput(String command, long timeout, TimeUnit unit, LineCallback callback) {
        return getExecutor().executeWithOutput(command, timeout, unit, callback);
    }

    // ==================== 包管理器操作 ====================

    /**
     * 检测当前系统上可用的包管理器
     *
     * @return 可用包管理器类型列表
     */
    public static java.util.List<PackageManager.Type> detectPackageManagers() {
        return PackageManager.detect();
    }

    /**
     * 使用包管理器同步安装软件包
     *
     * @param packageId 包 ID
     * @return 安装结果
     */
    public static CmdResult installPackage(String packageId) {
        return PackageManager.install(packageId);
    }

    /**
     * 使用包管理器同步安装软件包（实时输出）
     *
     * @param packageId 包 ID
     * @param callback  实时输出回调
     * @return 安装结果
     */
    public static CmdResult installPackage(String packageId, LineCallback callback) {
        return PackageManager.install(packageId, callback);
    }

    /**
     * 使用包管理器异步安装软件包
     *
     * @param packageId 包 ID
     * @param callback  结果回调
     */
    public static void installPackageAsync(String packageId, CmdCallback callback) {
        PackageManager.installAsync(packageId, callback);
    }

    /**
     * 使用指定包管理器同步安装软件包
     *
     * @param type      包管理器类型
     * @param packageId 包 ID
     * @return 安装结果
     */
    public static CmdResult installPackageWith(PackageManager.Type type, String packageId) {
        return PackageManager.installWith(type, packageId);
    }
}
