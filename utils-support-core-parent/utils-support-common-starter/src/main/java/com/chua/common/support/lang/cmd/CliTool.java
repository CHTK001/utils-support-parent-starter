package com.chua.common.support.lang.cmd;

import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
* 命令行软件的统一封装，补齐"软件在哪、版本多少、怎么装"这缺失的第一公里，
* 并把参数组装与进程执行用 {@code String[]} 直连。
*
* <p>此前调用一个外部 CLI 程序，各模块要重复实现四件事：扫描 PATH 找可执行文件、
* 探测版本、拼命令行字符串（含引号转义）、处理超时与错误。本类把这些收敛为一处，
* 接入新工具只需提供一份 {@link CliToolDescriptor} 描述，无需编写子类。</p>
*
* <h3>使用示例</h3>
* <pre>{@code
* CliTool tshark = new CliTool(CliToolDescriptor.builder("tshark")
*         .versionArgs("--version")
*         .candidateDirs("C:\\Program Files\\Wireshark")
*         .build());
*
* if (tshark.isAvailable()) {
*     CmdResult result = tshark.execute("-r", "capture.pcap", "-T", "json");
* }
* }</pre>
*
* <h3>子类化</h3>
* <p>当某个工具的输出需要结构化解析（如 ffprobe 的 JSON）或版本格式特殊时，
* 继承本类并覆写 {@link #parseVersion(CmdResult)} 等钩子即可，
* 定位、执行、安装等能力自动继承。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class CliTool {

    /** 版本探测的超时时间（秒） */
    private static final long VERSION_TIMEOUT_SECONDS = 10L;

    /** 工具描述 */
    protected final CliToolDescriptor descriptor;

    /** 显式指定的可执行文件路径，优先级高于自动定位 */
    private volatile String explicitPath;

    /** 定位结果缓存，null 表示尚未定位或定位失败 */
    private volatile Path resolvedPath;

    /** 版本缓存 */
    private volatile CliVersion cachedVersion;

    /** 固定参数模板（每次执行都会附加在可执行文件之后），null 表示未配置 */
    private volatile List<String> fixedArgs;

    /**
    * 创建 CLI 工具实例。
    *
    * @param descriptor 工具描述
     */
    public CliTool(@Nonnull CliToolDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("CliToolDescriptor must not be null");
        }
        this.descriptor = descriptor;
    }

    // ==================== 元信息 ====================

    /**
    * 获取工具唯一标识。
    *
    * @return 工具名称
     */
    @Nonnull
    public String name() {
        return descriptor.name();
    }

    /**
    * 获取工具展示名称。
    *
    * @return 展示名称
     */
    @Nonnull
    public String displayName() {
        return descriptor.displayName();
    }

    /**
    * 获取工具描述。
    *
    * @return 描述对象
     */
    @Nonnull
    public CliToolDescriptor descriptor() {
        return descriptor;
    }

    /**
    * 显式指定可执行文件路径，覆盖自动定位结果。
    *
    * @param path 可执行文件完整路径
    * @return this，便于链式调用
     */
    @Nonnull
    public CliTool withExecutablePath(@Nullable String path) {
        this.explicitPath = path;
        this.resolvedPath = null;
        this.cachedVersion = null;
        ExecutableLocator.clearCache();
        return this;
    }

    /**
    * 配置固定参数模板。
    *
    * <p>这些参数会在每次执行时自动附加在可执行文件之后、调用方传入的参数之前，
    * 适合封装需要固定全局选项的 CLI 软件（如 {@code --model xx --no-color}）。
    * 重复调用会整体替换；传空数组或 null 清除固定参数。</p>
    *
    * @param args 固定参数
    * @return this，便于链式调用
     */
    @Nonnull
    public CliTool withFixedArgs(@Nullable String... args) {
        if (args == null || args.length == 0) {
            this.fixedArgs = null;
        } else {
            this.fixedArgs = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)));
        }
        return this;
    }

    // ==================== 第一公里：定位、版本、安装 ====================

    /**
    * 定位可执行文件的绝对路径。
    *
    * <p>结果会被缓存，同一进程内重复调用不会重复扫描磁盘。</p>
    *
    * @return 可执行文件路径，未找到时返回 {@link Optional#empty()}
     */
    @Nonnull
    public Optional<Path> locate() {
        Path cached = resolvedPath;
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Path> found = ExecutableLocator.locate(descriptor.locateRequest(explicitPath));
        if (found.isPresent()) {
            resolvedPath = found.get();
        }
        return found;
    }

    /**
    * 判断工具是否可用。
    *
    * <p>可用意味着两件事：可执行文件能被找到，且版本满足
    * {@link CliToolDescriptor#minVersion()}（若设置了的话）。
    * 版本探测结果同样会被缓存。</p>
    *
    * @return 可用返回 true
     */
    public boolean isAvailable() {
        if (!locate().isPresent()) {
            return false;
        }
        CliVersion minVersion = descriptor.minVersion();
        if (minVersion == null) {
            return true;
        }
        return version().atLeast(minVersion);
    }

    /**
    * 获取工具版本。
    *
    * <p>通过执行描述中配置的 {@link CliToolDescriptor#versionArgs()} 并解析输出得到，
    * 结果缓存。工具未安装时返回 {@link CliVersion#unknown()}。</p>
    *
    * @return 版本对象，不会返回 null
     */
    @Nonnull
    public CliVersion version() {
        CliVersion cached = cachedVersion;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedVersion != null) {
                return cachedVersion;
            }
            if (!locate().isPresent()) {
                cachedVersion = CliVersion.unknown();
                return cachedVersion;
            }
            String[] versionArgs = descriptor.versionArgs().toArray(new String[0]);
            CmdResult result = executeInternal(versionArgs, VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            cachedVersion = parseVersion(result);
            return cachedVersion;
        }
    }

    /**
    * 使用系统包管理器安装该工具。
    *
    * <p>需要在描述中配置 {@link CliToolDescriptor#installPackageId()}。
    * 安装完成后会清空定位缓存，使下一次调用重新查找。</p>
    *
    * @return 安装结果
     */
    @Nonnull
    public CmdResult install() {
        String packageId = descriptor.installPackageId();
        if (StringUtils.isNullOrEmpty(packageId)) {
            return CmdResult.builder()
                    .exitCode(CmdResult.EXIT_CODE_ERROR)
                    .command(descriptor.name())
                    .startTime(System.currentTimeMillis())
                    .endTime(System.currentTimeMillis())
                    .throwable(new UnsupportedOperationException(
                            "工具 " + displayName() + " 未配置安装包的 ID，无法自动安装"))
                    .build();
        }

        CmdResult result = PackageManager.install(packageId);
        if (result.isSuccess()) {
            ExecutableLocator.clearCache();
            this.resolvedPath = null;
            this.cachedVersion = null;
        }
        return result;
    }

    // ==================== 执行 ====================

    /**
    * 创建链式调用请求，默认超时取描述中的配置。
    *
    * @return 请求对象
     */
    @Nonnull
    public CliRequest request() {
        return new CliRequest(this);
    }

    /**
    * 以默认超时执行指定参数。
    *
    * @param args 命令行参数
    * @return 执行结果
    * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public CmdResult execute(@Nonnull String... args) {
        return request().args(args).execute();
    }

    /**
    * 以指定超时执行。
    *
    * @param timeout 超时值
    * @param unit    时间单位
    * @param args    命令行参数
    * @return 执行结果
    * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public CmdResult execute(long timeout, @Nonnull TimeUnit unit, @Nonnull String... args) {
        return request().args(args).timeout(timeout, unit).execute();
    }

    /**
    * 执行并逐行接收输出。
    *
    * @param callback 逐行输出回调
    * @param args     命令行参数
    * @return 执行结果
    * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    public CmdResult executeWithOutput(@Nonnull LineCallback callback, @Nonnull String... args) {
        return request().args(args).executeWithOutput(callback);
    }

    // ==================== 子类钩子 ====================

    /**
    * 从版本探测的输出中解析版本号。
    *
    * <p>默认实现：优先从 stdout 提取，stdout 无内容时回退到 stderr
    * （部分工具把版本信息写到标准错误），再按描述中配置的正则提取。
    * 子类可覆写此方法以适配特殊的版本输出格式。</p>
    *
    * @param result 版本探测的执行结果
    * @return 解析出的版本，解析失败返回 {@link CliVersion#unknown()}
     */
    @Nonnull
    protected CliVersion parseVersion(@Nonnull CmdResult result) {
        String text = result.getStdout();
        if (StringUtils.isNullOrEmpty(text)) {
            text = result.getStderr();
        }
        if (StringUtils.isNullOrEmpty(text)) {
            return CliVersion.unknown();
        }
        Pattern pattern = descriptor.versionPattern();
        return pattern != null ? CliVersion.parse(text, pattern) : CliVersion.parse(text);
    }

    /**
    * 组装完整命令行，首元素为可执行文件路径，其后为参数。
    *
    * <p>子类可覆写以在参数前后插入固定的全局选项。</p>
    *
    * @param args 调用方传入的参数
    * @return 完整命令行数组
    * @throws IllegalStateException 工具不可用时抛出
     */
    @Nonnull
    protected String[] buildCommandLine(@Nonnull String[] args) {
        Path executable = requireExecutable();
        List<String> fixed = fixedArgs;
        int fixedLen = fixed == null ? 0 : fixed.size();
        String[] commandLine = new String[fixedLen + args.length + 1];
        commandLine[0] = executable.toString();
        if (fixedLen > 0) {
            for (int i = 0; i < fixedLen; i++) {
                commandLine[i + 1] = fixed.get(i);
            }
        }
        System.arraycopy(args, 0, commandLine, fixedLen + 1, args.length);
        return commandLine;
    }

    // ==================== 内部方法 ====================

    /**
    * 获取可执行文件路径，不可用时抛出明确异常。
    *
    * @return 可执行文件路径
    * @throws IllegalStateException 未安装或定位失败时抛出
     */
    @Nonnull
    private Path requireExecutable() {
        Optional<Path> found = locate();
        if (!found.isPresent()) {
            throw new IllegalStateException("命令行工具 " + displayName()
                    + " 未找到，请确认已安装，或通过环境变量 "
                    + (descriptor.envKey() != null ? descriptor.envKey() : "<未配置>")
                    + " 指定其路径，也可调用 install() 自动安装");
        }
        return found.get();
    }

    /**
    * 执行入口，供 {@link CliRequest} 调用。
    *
    * @param args    参数
    * @param timeout 超时值
    * @param unit    超时单位
    * @return 执行结果
     */
    @Nonnull
    CmdResult executeInternal(@Nonnull String[] args, long timeout, @Nullable TimeUnit unit) {
        return executeInternal(args, timeout, unit, null, null, null);
    }

    /**
    * 执行入口（扩展参数版本），供 {@link CliRequest} 调用。
    *
    * @param args             参数
    * @param timeout          超时值
    * @param unit             超时单位
    * @param workingDirectory 工作目录，可为 null
    * @param environment      附加环境变量，可为 null
    * @param input            标准输入内容，可为 null
    * @return 执行结果
     */
    @Nonnull
    CmdResult executeInternal(@Nonnull String[] args, long timeout, @Nullable TimeUnit unit,
                              @Nullable File workingDirectory, @Nullable Map<String, String> environment,
                              @Nullable String input) {
        String[] commandLine = buildCommandLine(args);
        if (timeout > 0 && unit != null) {
            return CmdExecutors.execute(commandLine, timeout, unit, workingDirectory, environment, input);
        }
        return CmdExecutors.execute(commandLine, 0, null, workingDirectory, environment, input);
    }

    /**
    * 带实时输出的执行入口，供 {@link CliRequest} 调用。
    *
    * @param args     参数
    * @param timeout  超时值
    * @param unit     超时单位
    * @param callback 逐行回调
    * @return 执行结果
     */
    @Nonnull
    CmdResult executeWithOutputInternal(@Nonnull String[] args, long timeout,
                                        @Nullable TimeUnit unit, @Nonnull LineCallback callback) {
        return executeWithOutputInternal(args, timeout, unit, callback, null, null, null);
    }

    /**
    * 带实时输出的执行入口（扩展参数版本），供 {@link CliRequest} 调用。
    *
    * @param args             参数
    * @param timeout          超时值
    * @param unit             超时单位
    * @param callback         逐行回调
    * @param workingDirectory 工作目录，可为 null
    * @param environment      附加环境变量，可为 null
    * @param input            标准输入内容，可为 null
    * @return 执行结果
     */
    @Nonnull
    CmdResult executeWithOutputInternal(@Nonnull String[] args, long timeout,
                                        @Nullable TimeUnit unit, @Nonnull LineCallback callback,
                                        @Nullable File workingDirectory, @Nullable Map<String, String> environment,
                                        @Nullable String input) {
        String[] commandLine = buildCommandLine(args);
        if (timeout > 0 && unit != null) {
            return CmdExecutors.executeWithOutput(commandLine, timeout, unit, callback,
                    workingDirectory, environment, input);
        }
        return CmdExecutors.executeWithOutput(commandLine, 0, null, callback,
                workingDirectory, environment, input);
    }

    /**
    * 异步执行入口，供 {@link CliRequest} 调用。
    *
    * @param args     参数
    * @param timeout  超时值
    * @param unit     超时单位
    * @param callback 结果回调
     */
    void executeAsyncInternal(@Nonnull String[] args, long timeout,
                              @Nullable TimeUnit unit, @Nonnull CmdCallback callback) {
        executeAsyncInternal(args, timeout, unit, callback, null, null, null);
    }

    /**
    * 异步执行入口（扩展参数版本），供 {@link CliRequest} 调用。
    *
    * @param args             参数
    * @param timeout          超时值
    * @param unit             超时单位
    * @param callback         结果回调
    * @param workingDirectory 工作目录，可为 null
    * @param environment      附加环境变量，可为 null
    * @param input            标准输入内容，可为 null
     */
    void executeAsyncInternal(@Nonnull String[] args, long timeout,
                              @Nullable TimeUnit unit, @Nonnull CmdCallback callback,
                              @Nullable File workingDirectory, @Nullable Map<String, String> environment,
                              @Nullable String input) {
        String[] commandLine = buildCommandLine(args);
        if (timeout > 0 && unit != null) {
            CmdExecutors.executeAsync(commandLine, timeout, unit, callback,
                    workingDirectory, environment, input);
        } else {
            CmdExecutors.executeAsync(commandLine, 0, null, callback,
                    workingDirectory, environment, input);
        }
    }

    @Override
    public String toString() {
        return "CliTool{name='" + name() + "', available=" + locate().isPresent()
                + ", version=" + (cachedVersion != null ? cachedVersion : "unknown") + "}";
    }
}
