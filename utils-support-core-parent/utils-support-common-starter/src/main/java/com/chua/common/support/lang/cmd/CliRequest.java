package com.chua.common.support.lang.cmd;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一次 CLI 调用的请求描述，采用链式风格组装参数与超时策略。
 *
 * <p>参数以数组形式直接传给进程，不经 Shell 解析、不做引号拼接，
 * 因此参数中的空格、中文、特殊字符都无需转义，也不存在命令注入风险。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CmdResult result = tshark.request()
 *         .args("-r", "capture.pcap")
 *         .args("-T", "json")
 *         .timeout(30, TimeUnit.SECONDS)
 *         .execute();
 *
 * // 实时输出
 * tshark.request()
 *         .args("--version")
 *         .executeWithOutput(line -> System.out.println(line));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CliRequest {

    /** 所属的 CLI 工具 */
    private final CliTool tool;
    /** 参数列表 */
    private final List<String> args = new ArrayList<>();
    /** 超时值，小于等于 0 表示不超时 */
    private long timeout;
    /** 超时单位 */
    private TimeUnit unit;

    /**
     * 创建请求实例
     *
     * @param tool 所属的 CLI 工具
     */
    CliRequest(@Nonnull CliTool tool) {
        this.tool = tool;
        this.timeout = tool.descriptor().defaultTimeoutSeconds();
        this.unit = TimeUnit.SECONDS;
    }

    /**
     * 追加单个参数。
     *
     * @param arg 参数
     * @return this
     */
    @Nonnull
    public CliRequest arg(@Nonnull String arg) {
        if (arg != null) {
            this.args.add(arg);
        }
        return this;
    }

    /**
     * 追加多个参数。
     *
     * @param args 参数数组
     * @return this
     */
    @Nonnull
    public CliRequest args(@Nonnull String... args) {
        if (args != null) {
            Collections.addAll(this.args, args);
        }
        return this;
    }

    /**
     * 追加参数集合。
     *
     * @param args 参数集合
     * @return this
     */
    @Nonnull
    public CliRequest args(@Nonnull Collection<String> args) {
        if (args != null) {
            this.args.addAll(args);
        }
        return this;
    }

    /**
     * 设置超时时间，覆盖工具的默认超时。
     *
     * @param timeout 超时值
     * @param unit    时间单位
     * @return this
     */
    @Nonnull
    public CliRequest timeout(long timeout, @Nonnull TimeUnit unit) {
        this.timeout = timeout;
        this.unit = unit;
        return this;
    }

    /**
     * 设置不超时，适用于执行时间不可预期的长任务。
     *
     * <p>请谨慎使用：被调程序若永不退出，当前线程会一直阻塞。</p>
     *
     * @return this
     */
    @Nonnull
    public CliRequest noTimeout() {
        this.timeout = 0;
        this.unit = TimeUnit.SECONDS;
        return this;
    }

    /**
     * 获取当前累积的参数列表。
     *
     * @return 参数列表副本
     */
    @Nonnull
    public List<String> args() {
        return Collections.unmodifiableList(new ArrayList<>(args));
    }

    /**
     * 获取超时值。
     *
     * @return 超时值，小于等于 0 表示不超时
     */
    public long timeout() {
        return timeout;
    }

    /**
     * 获取超时单位。
     *
     * @return 时间单位
     */
    @Nonnull
    public TimeUnit unit() {
        return unit;
    }

    /**
     * 组装完整的命令行数组，首元素为可执行文件路径，其后为参数。
     *
     * @return 命令行数组
     * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public String[] commandLine() {
        return tool.buildCommandLine(toArgArray());
    }

    /**
     * 同步执行。
     *
     * @return 执行结果
     * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public CmdResult execute() {
        return tool.executeInternal(toArgArray(), timeout, unit);
    }

    /**
     * 同步执行并逐行接收输出。
     *
     * @param callback 逐行输出回调
     * @return 执行结果
     * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public CmdResult executeWithOutput(@Nonnull LineCallback callback) {
        return tool.executeWithOutputInternal(toArgArray(), timeout, unit, callback);
    }

    /**
     * 异步执行。
     *
     * @return 异步结果
     * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public CompletableFuture<CmdResult> executeAsync() {
        CompletableFuture<CmdResult> future = new CompletableFuture<>();
        tool.executeAsyncInternal(toArgArray(), timeout, unit, new CmdCallback() {
            @Override
            /** OnComplete */
            public void onComplete(CmdResult result) {
                future.complete(result);
            }

            @Override
            /** On记录错误 */
            public void onError(String cmd, Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    /**
     * 把累积的参数转换为数组。
     *
     * @return 参数数组
     */
    @Nonnull
    private String[] toArgArray() {
        return args.toArray(new String[0]);
    }

    @Override
    public String toString() {
        return "CliRequest{tool=" + tool.name() + ", args=" + Arrays.toString(toArgArray())
                + ", timeout=" + timeout + " " + unit + "}";
    }
}
