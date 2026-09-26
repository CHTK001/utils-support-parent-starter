package com.chua.network.support.tshark.cli;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * tshark 命令行工具的不可变配置快照。
 *
 * <p>所有字段在构造时即已完成三级查找（系统属性 &rarr; 环境变量 &rarr;
 * {@link DirectoryPollerEnvironment}）与默认值回退，因此本对象在运行期不再读取外部配置，
 * 便于把"同一份配置"稳定地传给定位、下载、安装三个阶段。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * // 全部走默认
 * TsharkCliConfig config = TsharkCliConfig.defaults();
 *
 * // 覆盖超时与镜像
 * config = TsharkCliConfig.builder()
 *         .downloadTimeoutSeconds(1800)
 *         .mirrors(List.of("https://mirrors.aliyun.com/wireshark"))
 *         .build();
 *
 * // 从轮询目录环境配置派生
 * config = TsharkCliConfig.from(directoryPollerEnvironment);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class TsharkCliConfig {

    /**
     * tshark 装配级别。
     *
     * <p>三级策略的顺序可配置，原因是各级的<b>失败代价差异极大</b>：
     * 定位失败是瞬时的，包管理器安装在受限环境下可能耗时数十秒才失败
     * （实测 winget 在无管理员权限时约 36 秒后失败），
     * 而已知镜像可达时下载只需几秒。默认顺序对"首次在干净机器上装"最合适，
     * 但对"已配好内网镜像"的环境偏慢。</p>
     */
    public enum Stage {

        /**
         * 从显式路径、环境变量、PATH、常见安装目录定位
         */
        LOCATE("locate"),

        /**
         * 用系统包管理器安装
         */
        PACKAGE_MANAGER("packageManager"),

        /**
         * 从镜像下载并安装
         */
        DOWNLOAD("download");

        /**
         * 配置项中的名称
         */
        private final String token;

        /**
         * 构造枚举值。
         *
         * @param token 配置项中的名称
         */
        Stage(String token) {
            this.token = token;
        }

        /**
         * 配置项中的名称。
         *
         * @return 名称
         */
        @Nonnull
        public String token() {
            return token;
        }

        /**
         * 按名称解析，忽略大小写。
         *
         * @param token 名称
         * @return 对应的级别，名称非法返回 {@code null}
         */
        @Nullable
        public static Stage of(@Nullable String token) {
            if (token == null) {
                return null;
            }
            for (Stage stage : values()) {
                if (stage.token.equalsIgnoreCase(token.trim())) {
                    return stage;
                }
            }
            return null;
        }
    }

    /**
     * tshark 可执行文件显式路径，为空表示自动定位
     */
    private final String binary;

    /**
     * 找不到 tshark 时是否自动安装
     */
    private final boolean autoInstall;

    /**
     * 便携版安装根目录
     */
    private final Path installDir;

    /**
     * 要安装的 Wireshark 版本
     */
    private final String version;

    /**
     * 镜像基址列表，按顺序尝试
     */
    private final List<String> mirrors;

    /**
     * 完整下载地址，为空表示按版本与镜像拼装
     */
    private final String explicitUrl;

    /**
     * 下载制品的期望 SHA-256，为空表示跳过校验
     */
    private final String sha256;

    /**
     * 强制指定包管理器，为空表示自动探测
     */
    private final String packageManager;

    /**
     * 装配级别的执行顺序
     */
    private final List<Stage> stages;

    /**
     * 单次 tshark 命令执行超时（秒）
     */
    private final long execTimeoutSeconds;

    /**
     * 版本探测超时（秒）
     */
    private final long versionTimeoutSeconds;

    /**
     * 单个下载制品的超时（秒）
     */
    private final long downloadTimeoutSeconds;

    /**
     * 解压或静默安装的超时（秒）
     */
    private final long installTimeoutSeconds;

    /**
     * 使用默认配置构造。
     *
     * @param builder 构建器
     */
    private TsharkCliConfig(Builder builder) {
        this.binary = blankToNull(builder.binary);
        this.autoInstall = builder.autoInstall;
        this.installDir = builder.installDir;
        this.version = builder.version;
        this.mirrors = builder.mirrors;
        this.explicitUrl = blankToNull(builder.explicitUrl);
        this.sha256 = blankToNull(builder.sha256);
        this.packageManager = blankToNull(builder.packageManager);
        this.stages = resolveStages(builder.stages);
        this.execTimeoutSeconds = positive(builder.execTimeoutSeconds, TsharkSettings.DEFAULT_EXEC_TIMEOUT_SECONDS);
        this.versionTimeoutSeconds = positive(builder.versionTimeoutSeconds, TsharkSettings.DEFAULT_VERSION_TIMEOUT_SECONDS);
        this.downloadTimeoutSeconds = positive(builder.downloadTimeoutSeconds, TsharkSettings.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS);
        this.installTimeoutSeconds = positive(builder.installTimeoutSeconds, TsharkSettings.DEFAULT_INSTALL_TIMEOUT_SECONDS);
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 全部字段取默认值的配置。
     *
     * @return 默认配置
     */
    @Nonnull
    public static TsharkCliConfig defaults() {
        return builder().build();
    }

    /**
     * 从轮询目录环境配置派生一份 tshark 配置。
     *
     * @param environment 环境配置，可为空
     * @return 配置快照
     */
    @Nonnull
    public static TsharkCliConfig from(@Nullable DirectoryPollerEnvironment environment) {
        return builder()
                .binary(TsharkSettings.string(TsharkSettings.KEY_BINARY, environment, null))
                .autoInstall(TsharkSettings.bool(TsharkSettings.KEY_AUTO_INSTALL, environment, true))
                .installDir(resolveInstallDir(TsharkSettings.string(TsharkSettings.KEY_INSTALL_DIR, environment, null)))
                .version(TsharkSettings.string(TsharkSettings.KEY_DOWNLOAD_VERSION, environment,
                        TsharkSettings.DEFAULT_DOWNLOAD_VERSION))
                .mirrors(TsharkSettings.mirrors(environment))
                .explicitUrl(TsharkSettings.string(TsharkSettings.KEY_DOWNLOAD_URL, environment, null))
                .sha256(TsharkSettings.string(TsharkSettings.KEY_DOWNLOAD_SHA256, environment, null))
                .packageManager(TsharkSettings.string(TsharkSettings.KEY_PACKAGE_MANAGER, environment, null))
                .stages(parseStages(TsharkSettings.string(TsharkSettings.KEY_STAGES, environment, null)))
                .execTimeoutSeconds(TsharkSettings.seconds(TsharkSettings.KEY_EXEC_TIMEOUT_SECONDS, environment,
                        TsharkSettings.DEFAULT_EXEC_TIMEOUT_SECONDS))
                .versionTimeoutSeconds(TsharkSettings.seconds(TsharkSettings.KEY_VERSION_TIMEOUT_SECONDS, environment,
                        TsharkSettings.DEFAULT_VERSION_TIMEOUT_SECONDS))
                .downloadTimeoutSeconds(TsharkSettings.seconds(TsharkSettings.KEY_DOWNLOAD_TIMEOUT_SECONDS, environment,
                        TsharkSettings.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS))
                .installTimeoutSeconds(TsharkSettings.seconds(TsharkSettings.KEY_INSTALL_TIMEOUT_SECONDS, environment,
                        TsharkSettings.DEFAULT_INSTALL_TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 解析便携版安装根目录。
     *
     * <p>默认落在 {@code ~/.chua/tshark}，放在用户目录下而非安装目录，
     * 避免无管理员权限时写入失败，也便于卸载时整体删除。</p>
     *
     * @param configured 配置的目录，为空时取默认
     * @return 安装根目录
     */
    @Nonnull
    public static Path resolveInstallDir(@Nullable String configured) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim());
        }
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".chua", "tshark");
    }

    /**
     * tshark 可执行文件显式路径。
     *
     * @return 显式路径，未配置返回 {@code null}
     */
    @Nullable
    public String binary() {
        return binary;
    }

    /**
     * 是否自动安装。
     *
     * @return 允许自动安装返回 true
     */
    public boolean autoInstall() {
        return autoInstall;
    }

    /**
     * 便携版安装根目录。
     *
     * @return 安装根目录
     */
    @Nonnull
    public Path installDir() {
        return installDir;
    }

    /**
     * 要安装的 Wireshark 版本。
     *
     * @return 版本号
     */
    @Nonnull
    public String version() {
        return version;
    }

    /**
     * 镜像基址列表。
     *
     * @return 镜像列表
     */
    @Nonnull
    public List<String> mirrors() {
        return mirrors;
    }

    /**
     * 完整下载地址。
     *
     * @return 下载地址，未配置返回 {@code null}
     */
    @Nullable
    public String explicitUrl() {
        return explicitUrl;
    }

    /**
     * 下载制品的期望 SHA-256。
     *
     * @return 十六进制校验值，未配置返回 {@code null}
     */
    @Nullable
    public String sha256() {
        return sha256;
    }

    /**
     * 强制指定的包管理器。
     *
     * @return 包管理器名，未配置返回 {@code null}
     */
    @Nullable
    public String packageManager() {
        return packageManager;
    }

    /**
     * 单次 tshark 命令执行超时。
     *
     * @return 超时秒数
     */
    public long execTimeoutSeconds() {
        return execTimeoutSeconds;
    }

    /**
     * 版本探测超时。
     *
     * @return 超时秒数
     */
    public long versionTimeoutSeconds() {
        return versionTimeoutSeconds;
    }

    /**
     * 单个下载制品的超时。
     *
     * @return 超时秒数
     */
    public long downloadTimeoutSeconds() {
        return downloadTimeoutSeconds;
    }

    /**
     * 解压或静默安装的超时。
     *
     * @return 超时秒数
     */
    public long installTimeoutSeconds() {
        return installTimeoutSeconds;
    }

    /**
     * 把空白字符串归一为 {@code null}。
     *
     * @param value 原始值
     * @return 归一后的值
     */
    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 装配级别的执行顺序。
     *
     * @return 级别列表，始终包含至少一项
     */
    @Nonnull
    public List<Stage> stages() {
        return stages;
    }

    /**
     * 解析逗号分隔的装配级别配置。
     *
     * @param configured 配置值，可为空
     * @return 级别列表；配置为空返回 {@code null} 以交由默认值处理
     */
    @Nullable
    private static List<Stage> parseStages(@Nullable String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        List<Stage> stages = new java.util.ArrayList<>();
        for (String token : configured.split(",")) {
            Stage stage = Stage.of(token);
            if (stage == null) {
                System.err.println("[TsharkCliConfig] 忽略无法识别的装配级别: " + token.trim());
                continue;
            }
            if (!stages.contains(stage)) {
                stages.add(stage);
            }
        }
        return stages;
    }

    /**
     * 归一化装配级别：去重保序，并强制包含 {@link Stage#LOCATE}。
     *
     * <p>定位是所有后续级别的前提（没有可执行文件就无法验证安装结果），
     * 因此即便配置里没写也会补上；配置全部非法时回退为默认三级。</p>
     *
     * @param configured 配置的级别列表，可为空
     * @return 归一化后的级别列表
     */
    @Nonnull
    private static List<Stage> resolveStages(@Nullable List<Stage> configured) {
        if (configured == null || configured.isEmpty()) {
            return List.of(Stage.LOCATE, Stage.PACKAGE_MANAGER, Stage.DOWNLOAD);
        }
        List<Stage> result = new java.util.ArrayList<>();
        for (Stage stage : configured) {
            if (stage != null && !result.contains(stage)) {
                result.add(stage);
            }
        }
        if (result.isEmpty()) {
            return List.of(Stage.LOCATE, Stage.PACKAGE_MANAGER, Stage.DOWNLOAD);
        }
        if (!result.contains(Stage.LOCATE)) {
            result.add(0, Stage.LOCATE);
        }
        return List.copyOf(result);
    }

    /**
     * 归一化秒数，非法或非正值回退默认值。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 归一后的秒数
     */
    private static long positive(long value, long defaultValue) {
        return value <= 0 ? defaultValue : value;
    }

    @Override
    public String toString() {
        return "TsharkCliConfig{version='" + version + '\''
                + ", autoInstall=" + autoInstall
                + ", installDir=" + installDir
                + ", binary=" + binary
                + ", execTimeout=" + execTimeoutSeconds + "s"
                + ", downloadTimeout=" + downloadTimeoutSeconds + "s"
                + ", installTimeout=" + installTimeoutSeconds + "s"
                + ", mirrors=" + mirrors.size()
                + '}';
    }

    /**
     * {@link TsharkCliConfig} 构建器，未显式设置的字段取默认值。
     */
    public static final class Builder {

        /**
         * 以一份已有配置为基准，便于"保留部分显式设置、其余用配置来源覆盖"。
         *
         * @param base 基准配置，为空时使用默认值
         * @return this
         */
        @Nonnull
        public Builder from(@Nullable TsharkCliConfig base) {
            if (base == null) {
                return this;
            }
            this.binary = base.binary;
            this.autoInstall = base.autoInstall;
            this.installDir = base.installDir;
            this.version = base.version;
            this.mirrors = base.mirrors;
            this.explicitUrl = base.explicitUrl;
            this.sha256 = base.sha256;
            this.packageManager = base.packageManager;
            this.stages = base.stages;
            this.execTimeoutSeconds = base.execTimeoutSeconds;
            this.versionTimeoutSeconds = base.versionTimeoutSeconds;
            this.downloadTimeoutSeconds = base.downloadTimeoutSeconds;
            this.installTimeoutSeconds = base.installTimeoutSeconds;
            return this;
        }

        /**
         * tshark 可执行文件显式路径
         */
        private String binary;
        /**
         * 是否自动安装
         */
        private boolean autoInstall = true;
        /**
         * 便携版安装根目录
         */
        private Path installDir = resolveInstallDir(null);
        /**
         * 要安装的版本
         */
        private String version = TsharkSettings.DEFAULT_DOWNLOAD_VERSION;
        /**
         * 镜像列表
         */
        private List<String> mirrors = TsharkSettings.DEFAULT_MIRRORS;
        /**
         * 完整下载地址
         */
        private String explicitUrl;
        /**
         * 期望 SHA-256
         */
        private String sha256;
        /**
         * 强制包管理器
         */
        private String packageManager;
        /**
         * 装配级别顺序
         */
        private List<Stage> stages;
        /**
         * 执行超时（秒）
         */
        private long execTimeoutSeconds = TsharkSettings.DEFAULT_EXEC_TIMEOUT_SECONDS;
        /**
         * 版本探测超时（秒）
         */
        private long versionTimeoutSeconds = TsharkSettings.DEFAULT_VERSION_TIMEOUT_SECONDS;
        /**
         * 下载超时（秒）
         */
        private long downloadTimeoutSeconds = TsharkSettings.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS;
        /**
         * 安装超时（秒）
         */
        private long installTimeoutSeconds = TsharkSettings.DEFAULT_INSTALL_TIMEOUT_SECONDS;

        /**
         * 设置 tshark 可执行文件显式路径。
         *
         * @param binary 路径，为空表示自动定位
         * @return this
         */
        @Nonnull
        public Builder binary(@Nullable String binary) {
            this.binary = binary;
            return this;
        }

        /**
         * 设置是否自动安装。
         *
         * @param autoInstall true 表示允许自动安装
         * @return this
         */
        @Nonnull
        public Builder autoInstall(boolean autoInstall) {
            this.autoInstall = autoInstall;
            return this;
        }

        /**
         * 设置便携版安装根目录。
         *
         * @param installDir 安装根目录
         * @return this
         */
        @Nonnull
        public Builder installDir(@Nonnull Path installDir) {
            this.installDir = installDir;
            return this;
        }

        /**
         * 设置要安装的 Wireshark 版本。
         *
         * @param version 版本号
         * @return this
         */
        @Nonnull
        public Builder version(@Nonnull String version) {
            this.version = version;
            return this;
        }

        /**
         * 设置镜像基址列表。
         *
         * @param mirrors 镜像列表
         * @return this
         */
        @Nonnull
        public Builder mirrors(@Nonnull List<String> mirrors) {
            this.mirrors = mirrors == null || mirrors.isEmpty() ? TsharkSettings.DEFAULT_MIRRORS : List.copyOf(mirrors);
            return this;
        }

        /**
         * 设置完整下载地址，指定后忽略版本与镜像。
         *
         * @param explicitUrl 下载地址
         * @return this
         */
        @Nonnull
        public Builder explicitUrl(@Nullable String explicitUrl) {
            this.explicitUrl = explicitUrl;
            return this;
        }

        /**
         * 设置下载制品的期望 SHA-256。
         *
         * @param sha256 十六进制校验值
         * @return this
         */
        @Nonnull
        public Builder sha256(@Nullable String sha256) {
            this.sha256 = sha256;
            return this;
        }

        /**
         * 设置强制使用的包管理器。
         *
         * @param packageManager 包管理器名
         * @return this
         */
        @Nonnull
        public Builder packageManager(@Nullable String packageManager) {
            this.packageManager = packageManager;
            return this;
        }

        /**
         * 设置装配级别的执行顺序。
         *
         * <p>未包含 {@link Stage#LOCATE} 时会被自动补到首位。</p>
         *
         * @param stages 级别列表
         * @return this
         */
        @Nonnull
        public Builder stages(@Nullable List<Stage> stages) {
            this.stages = stages;
            return this;
        }

        /**
         * 设置单次 tshark 命令执行超时。
         *
         * @param seconds 超时秒数，非正值取默认值
         * @return this
         */
        @Nonnull
        public Builder execTimeoutSeconds(long seconds) {
            this.execTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置版本探测超时。
         *
         * @param seconds 超时秒数，非正值取默认值
         * @return this
         */
        @Nonnull
        public Builder versionTimeoutSeconds(long seconds) {
            this.versionTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置单个下载制品的超时。
         *
         * @param seconds 超时秒数，非正值取默认值
         * @return this
         */
        @Nonnull
        public Builder downloadTimeoutSeconds(long seconds) {
            this.downloadTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置解压或静默安装的超时。
         *
         * @param seconds 超时秒数，非正值取默认值
         * @return this
         */
        @Nonnull
        public Builder installTimeoutSeconds(long seconds) {
            this.installTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 构建配置实例。
         *
         * @return 配置快照
         */
        @Nonnull
        public TsharkCliConfig build() {
            return new TsharkCliConfig(this);
        }
    }
}
