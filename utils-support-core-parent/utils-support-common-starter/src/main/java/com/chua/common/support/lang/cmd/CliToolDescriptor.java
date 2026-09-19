package com.chua.common.support.lang.cmd;

import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 命令行软件的声明式描述，把"这个程序叫什么、装在哪、怎么问版本号"全部固化成元数据。
 *
 * <p>这是 CLI 软件封装通用化的核心：接入一个新的命令行程序时，
 * 只需要提供一份描述，无需编写任何子类，{@link CliTool} 即可提供定位、
 * 可用性探测、版本校验、执行与安装的完整能力。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CliToolDescriptor descriptor = CliToolDescriptor.builder("tshark")
 *         .displayName("Wireshark 命令行抓包工具")
 *         .executable("tshark")
 *         .envKey("TSHARK_BIN")
 *         .candidateDirs("C:\\Program Files\\Wireshark", "/usr/bin", "/usr/local/bin")
 *         .versionArgs("--version")
 *         .versionPattern(Pattern.compile("TShark \\(Wireshark\\) ([\\d.]+)"))
 *         .minVersion(CliVersion.of(3, 0))
 *         .defaultTimeout(30, TimeUnit.SECONDS)
 *         .installPackage("wireshark")
 *         .build();
 *
 * CliTool tshark = new CliTool(descriptor);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CliToolDescriptor {

    /**
     * 默认版本探测参数
    */
    private static final List<String> DEFAULT_VERSION_ARGS = Collections.singletonList("--version");

    /**
     * 默认超时时间（秒）
    */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60L;

    /**
     * 工具唯一标识
    */
    private final String name;
    /**
     * 展示名称
    */
    private final String displayName;
    /**
     * Windows 下的可执行文件名
    */
    private final String windowsExecutable;
    /**
     * 类 Unix 下的可执行文件名
    */
    private final String unixExecutable;
    /**
     * 不分平台的通用可执行文件名
    */
    private final String executable;
    /**
     * 用于覆盖定位结果的环境变量名
    */
    private final String envKey;
    /**
     * 候选安装目录
    */
    private final List<String> candidateDirs;
    /**
     * 版本探测参数
    */
    private final List<String> versionArgs;
    /**
     * 版本提取正则
    */
    private final Pattern versionPattern;
    /**
     * 最低版本要求
    */
    private final CliVersion minVersion;
    /**
     * 默认超时时间（秒）
    */
    private final long defaultTimeoutSeconds;
    /**
     * 包管理器安装时使用的包 ID
    */
    private final String installPackageId;
    /**
     * 选项契约，供强类型参数组装使用
    */
    private final List<CliOption> options;

    /**
     * 创建描述实例
     *
     * @param builder 构建器
     */
    private CliToolDescriptor(Builder builder) {
        this.name = builder.name;
        this.displayName = builder.displayName != null ? builder.displayName : builder.name;
        this.windowsExecutable = builder.windowsExecutable != null
                ? builder.windowsExecutable : builder.executable;
        this.unixExecutable = builder.unixExecutable != null
                ? builder.unixExecutable : builder.executable;
        this.executable = builder.executable;
        this.envKey = builder.envKey;
        this.candidateDirs = Collections.unmodifiableList(new ArrayList<>(builder.candidateDirs));
        this.versionArgs = Collections.unmodifiableList(new ArrayList<>(builder.versionArgs));
        this.versionPattern = builder.versionPattern;
        this.minVersion = builder.minVersion;
        this.defaultTimeoutSeconds = builder.defaultTimeoutSeconds;
        this.installPackageId = builder.installPackageId;
        this.options = Collections.unmodifiableList(new ArrayList<>(builder.options));
    }

    /**
     * 创建构建器。
     *
     * @param name 工具唯一标识，同时作为默认可执行文件名
     * @return 构建器
     */
    @Nonnull
    public static Builder builder(@Nonnull String name) {
        return new Builder(name);
    }

    /**
     * 获取工具唯一标识。
     *
     * @return 工具名称
     */
    @Nonnull
    public String name() {
        return name;
    }

    /**
     * 获取展示名称。
     *
     * @return 展示名称
     */
    @Nonnull
    public String displayName() {
        return displayName;
    }

    /**
     * 获取当前平台下的可执行文件名。
     *
     * @return 可执行文件名
     */
    @Nonnull
    public String executableName() {
        return OsFamily.current().isWindows() ? windowsExecutable : unixExecutable;
    }

    /**
     * 获取通用可执行文件名（不分平台）。
     *
     * @return 可执行文件名
     */
    @Nonnull
    public String executable() {
        return executable;
    }

    /**
     * 获取用于覆盖定位结果的环境变量名。
     *
     * @return 环境变量名，未设置返回 null
     */
    @Nullable
    public String envKey() {
        return envKey;
    }

    /**
     * 获取候选安装目录。
     *
     * @return 候选目录列表
     */
    @Nonnull
    public List<String> candidateDirs() {
        return candidateDirs;
    }

    /**
     * 获取版本探测参数。
     *
     * @return 参数列表，默认 {@code --version}
     */
    @Nonnull
    public List<String> versionArgs() {
        return versionArgs;
    }

    /**
     * 获取版本提取正则。
     *
     * @return 正则对象，未设置返回 null
     */
    @Nullable
    public Pattern versionPattern() {
        return versionPattern;
    }

    /**
     * 获取最低版本要求。
     *
     * @return 最低版本，未设置返回 null
     */
    @Nullable
    public CliVersion minVersion() {
        return minVersion;
    }

    /**
     * 获取默认超时时间。
     *
     * @return 超时秒数
     */
    public long defaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    /**
     * 获取包管理器安装用的包 ID。
     *
     * @return 包 ID，未设置返回 null
     */
    @Nullable
    public String installPackageId() {
        return installPackageId;
    }

    /**
     * 获取选项契约。
     *
     * @return 选项定义列表
     */
    @Nonnull
    public List<CliOption> options() {
        return options;
    }

    /**
     * 构造可执行文件查找请求。
     *
     * @param explicitPath 显式指定的路径，可为 null
     * @return 查找请求
     */
    @Nonnull
    public ExecutableLocator.LocateRequest locateRequest(@Nullable String explicitPath) {
        ExecutableLocator.LocateRequest.Builder builder =
                ExecutableLocator.LocateRequest.builder(executableName());
        if (!executable.equals(executableName())) {
            builder.alias(executable);
        }
        return builder
                .explicitPath(explicitPath)
                .envKey(envKey)
                .candidateDirs(candidateDirs)
                .build();
    }

    @Override
    public String toString() {
        return "CliToolDescriptor{name='" + name + "', executable='" + executableName() + "'}";
    }

    /**
     * {@link CliToolDescriptor} 构建器。
     */
    public static final class Builder {

        /**
         * 工具唯一标识
        */
        private final String name;
        /**
         * 展示名称
        */
        private String displayName;
        /**
         * 通用可执行名
        */
        private String executable;
        /**
         * Windows 可执行名
        */
        private String windowsExecutable;
        /**
         * Unix 可执行名
        */
        private String unixExecutable;
        /**
         * 环境变量键
        */
        private String envKey;
        /**
         * 候选目录，去重有序
        */
        private final Set<String> candidateDirs = new LinkedHashSet<>();
        /**
         * 版本参数
        */
        private final List<String> versionArgs = new ArrayList<>(DEFAULT_VERSION_ARGS);
        /**
         * 版本正则
        */
        private Pattern versionPattern;
        /**
         * 最低版本
        */
        private CliVersion minVersion;
        /**
         * 默认超时秒数
        */
        private long defaultTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        /**
         * 安装包 ID
        */
        private String installPackageId;
        /**
         * 选项契约，去重有序
        */
        private final Set<CliOption> options = new LinkedHashSet<>();

        /**
         * 创建构建器
         *
         * @param name 工具唯一标识
         */
        private Builder(String name) {
            this.name = name;
            this.executable = name;
        }

        /**
         * 设置展示名称，用于日志与错误信息。
         *
         * @param displayName 展示名称
         * @return this
         */
        @Nonnull
        public Builder displayName(@Nonnull String displayName) {
            this.displayName = displayName;
            return this;
        }

        /**
         * 设置可执行文件名，Windows 与类 Unix 通用。
         *
         * @param executable 可执行文件名
         * @return this
         */
        @Nonnull
        public Builder executable(@Nonnull String executable) {
            this.executable = executable;
            return this;
        }

        /**
         * 设置 Windows 下的可执行文件名，覆盖 {@link #executable(String)}。
         *
         * @param windowsExecutable Windows 可执行名
         * @return this
         */
        @Nonnull
        public Builder windowsExecutable(@Nonnull String windowsExecutable) {
            this.windowsExecutable = windowsExecutable;
            return this;
        }

        /**
         * 设置类 Unix 下的可执行文件名，覆盖 {@link #executable(String)}。
         *
         * @param unixExecutable Unix 可执行名
         * @return this
         */
        @Nonnull
        public Builder unixExecutable(@Nonnull String unixExecutable) {
            this.unixExecutable = unixExecutable;
            return this;
        }

        /**
         * 设置用于覆盖定位结果的环境变量名，便于运维侧指定安装位置。
         *
         * @param envKey 环境变量名
         * @return this
         */
        @Nonnull
        public Builder envKey(@Nullable String envKey) {
            this.envKey = envKey;
            return this;
        }

        /**
         * 追加候选安装目录，应对软件未加入 PATH 的情况，可多次调用。
         *
         * @param dirs 候选目录
         * @return this
         */
        @Nonnull
        public Builder candidateDirs(@Nonnull String... dirs) {
            for (String dir : dirs) {
                if (!StringUtils.isNullOrEmpty(dir)) {
                    this.candidateDirs.add(dir);
                }
            }
            return this;
        }

        /**
         * 设置版本探测参数，默认 {@code --version}。
         *
         * <p>部分老旧工具只认单横杠形式（如 {@code -version}），可通过此方法覆盖。</p>
         *
         * @param args 版本参数
         * @return this
         */
        @Nonnull
        public Builder versionArgs(@Nonnull String... args) {
            this.versionArgs.clear();
            Collections.addAll(this.versionArgs, args);
            return this;
        }

        /**
         * 设置版本提取正则，需包含第一个捕获组。
         *
         * @param versionPattern 版本正则
         * @return this
         */
        @Nonnull
        public Builder versionPattern(@Nullable Pattern versionPattern) {
            this.versionPattern = versionPattern;
            return this;
        }

        /**
         * 设置最低版本要求，低于此版本时 {@link CliTool#isAvailable()} 返回 false。
         *
         * @param minVersion 最低版本
         * @return this
         */
        @Nonnull
        public Builder minVersion(@Nullable CliVersion minVersion) {
            this.minVersion = minVersion;
            return this;
        }

        /**
         * 设置默认超时时间，默认 60 秒。
         *
         * @param timeout 超时值
         * @param unit    时间单位
         * @return this
         */
        @Nonnull
        public Builder defaultTimeout(long timeout, @Nonnull TimeUnit unit) {
            this.defaultTimeoutSeconds = unit.toSeconds(timeout);
            return this;
        }

        /**
         * 设置包管理器安装时使用的包 ID。
         *
         * @param installPackageId 包 ID
         * @return this
         */
        @Nonnull
        public Builder installPackage(@Nullable String installPackageId) {
            this.installPackageId = installPackageId;
            return this;
        }

        /**
         * 追加选项定义，用于强类型参数组装，可多次调用。
         *
         * @param options 选项定义
         * @return this
         */
        @Nonnull
        public Builder options(@Nonnull CliOption... options) {
            Collections.addAll(this.options, options);
            return this;
        }

        /**
         * 构建描述实例。
         *
         * @return 描述实例
         * @throws IllegalStateException 工具名称为空时抛出
         */
        @Nonnull
        public CliToolDescriptor build() {
            if (StringUtils.isNullOrEmpty(name)) {
                throw new IllegalStateException("CLI 工具名称不能为空");
            }
            return new CliToolDescriptor(this);
        }
    }
}
