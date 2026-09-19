package com.chua.common.support.lang.cmd;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 请求级工作目录，null 表示不指定 */
    private File workingDirectory;

    /** 请求级附加环境变量，null 表示不指定 */
    private Map<String, String> environment;

    /** 标准输入内容，null 表示不写入 */
    private String input;

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

    // ==================== 扩展参数：工作目录 / 环境变量 / 标准输入 ====================

    /**
     * 设置请求级工作目录。
     *
     * @param dir 工作目录，null 清除
     * @return this
     */
    @Nonnull
    public CliRequest workingDirectory(@Nullable Path dir) {
        this.workingDirectory = dir == null ? null : dir.toFile();
        return this;
    }

    /**
     * 设置请求级工作目录。
     *
     * @param dir 工作目录，null 清除
     * @return this
     */
    @Nonnull
    public CliRequest workingDirectory(@Nullable File dir) {
        this.workingDirectory = dir;
        return this;
    }

    /**
     * 添加单个附加环境变量。
     *
     * @param key   变量名
     * @param value 变量值
     * @return this
     */
    @Nonnull
    public CliRequest env(@Nonnull String key, @Nonnull String value) {
        if (environment == null) {
            environment = new HashMap<>();
        }
        environment.put(key, value);
        return this;
    }

    /**
     * 批量添加附加环境变量。
     *
     * @param env 环境变量集合，null 忽略
     * @return this
     */
    @Nonnull
    public CliRequest envs(@Nullable Map<String, String> env) {
        if (env != null) {
            if (environment == null) {
                environment = new HashMap<>();
            }
            environment.putAll(env);
        }
        return this;
    }

    /**
     * 设置写入进程标准输入的内容。
     *
     * @param input 标准输入内容，null 清除
     * @return this
     */
    @Nonnull
    public CliRequest input(@Nullable String input) {
        this.input = input;
        return this;
    }

    /**
     * 以键值对形式追加参数，展开为 {@code --key value}（长选项风格）。
     *
     * <p>键已带 {@code -} 前缀时按原样使用；值为 null 时只输出键（布尔开关）。
     * 适合封装 {@code --model xxx --steps 8} 这类选项式 CLI 软件。</p>
     *
     * @param options 键值对参数
     * @return this
     */
    @Nonnull
    public CliRequest args(@Nonnull Map<String, Object> options) {
        if (options != null) {
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null || key.isEmpty()) {
                    continue;
                }
                this.args.add(key.startsWith("-") ? key : "--" + key);
                if (value != null) {
                    this.args.add(String.valueOf(value));
                }
            }
        }
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
        if (workingDirectory == null && environment == null && input == null) {
            return tool.executeInternal(toArgArray(), timeout, unit);
        }
        return tool.executeInternal(toArgArray(), timeout, unit, workingDirectory, environment, input);
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
        if (workingDirectory == null && environment == null && input == null) {
            return tool.executeWithOutputInternal(toArgArray(), timeout, unit, callback);
        }
        return tool.executeWithOutputInternal(toArgArray(), timeout, unit, callback,
                workingDirectory, environment, input);
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
        if (workingDirectory == null && environment == null && input == null) {
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
        } else {
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
            }, workingDirectory, environment, input);
        }
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
