package com.chua.network.support.tshark.cli;

import com.chua.common.support.lang.cmd.CliVersion;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.ExecutableLocator;
import com.chua.common.support.lang.cmd.PackageManager;
import com.chua.common.support.lang.cmd.tools.TsharkTool;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * tshark 命令行工具装配器：按三级策略把可执行的 tshark 交到调用方手上。
 *
 * <h3>三级策略</h3>
 * <ol>
 *   <li><b>定位</b> — 显式路径（{@code tshark.binary}）→ 环境变量 {@code TSHARK_BIN} →
 *       PATH → 常见安装目录（{@code C:\Program Files\Wireshark} 等）→ {@code where/which}。
 *       复用 {@link TsharkTool}，命中即返回并做版本校验。</li>
 *   <li><b>包管理器</b> — 依次尝试 winget / choco / brew / apt / dnf / yum / apk
 *       安装 {@code wireshark}，需要管理员权限，适用于已装过包管理器的传统环境。</li>
 *   <li><b>直接下载</b> — 前两级都失败时，从可配置镜像下载官方制品并装到
 *       {@code ~/.chua/tshark/<版本>}，适用于无管理员权限或无包管理器的环境。
 *       逐个镜像尝试，全部失败才判定不可用。</li>
 * </ol>
 *
 * <p><b>三级为什么都要有</b>：第一级覆盖运维已规范安装的场景；
 * 第二级覆盖允许安装软件的传统环境；第三级覆盖容器与受限终端。
 * 三级全部失败时返回带人工指引的 {@link TsharkProvisioningReport}，而不是抛出裸异常。</p>
 *
 * <p><b>超时</b>：每一级都有独立上界——定位依赖 {@link TsharkTool} 的 10 秒版本探测、
 * 包管理器安装 300 秒、下载与解压安装分别由
 * {@link TsharkCliConfig#downloadTimeoutSeconds()} 与
 * {@link TsharkCliConfig#installTimeoutSeconds()} 约束。整体调用不会无界等待。</p>
 *
 * <p><b>并发</b>：装配结果对整个进程共享，{@link #provision} 以
 * {@link ReentrantLock} 串行化，避免多个采集会话同时触发重复下载安装。</p>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * TsharkCliProvider provider = TsharkCliProvider.getInstance();
 * TsharkProvisioningReport report = provider.provision(TsharkCliConfig.defaults());
 * if (report.available()) {
 *     Path tshark = report.executable();
 * } else {
 *     log.warn(report.describe());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class TsharkCliProvider {

    /**
     * 单例
     */
    private static final TsharkCliProvider INSTANCE = new TsharkCliProvider();

    /**
     * 装配锁，防止并发重复下载安装
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 缓存的装配结果
     */
    private volatile TsharkProvisioningReport cached;

    /**
     * 缓存结果对应的配置指纹，配置变化时缓存失效
     */
    private volatile String cachedFingerprint;

    /**
     * 构造私有实例
     */
    private TsharkCliProvider() {
    }

    /**
     * 获取单例。
     *
     * @return 单例
     */
    @Nonnull
    public static TsharkCliProvider getInstance() {
        return INSTANCE;
    }

    /**
     * 按三级策略装配 tshark，命中缓存时直接返回。
     *
     * @param config 配置快照
     * @return 装配结果
     */
    @Nonnull
    public TsharkProvisioningReport provision(@Nonnull TsharkCliConfig config) {
        String fingerprint = fingerprint(config);
        TsharkProvisioningReport report = cached;
        if (report != null && fingerprint.equals(cachedFingerprint)) {
            return report;
        }
        lock.lock();
        try {
            report = cached;
            if (report != null && fingerprint.equals(cachedFingerprint)) {
                return report;
            }
            report = doProvision(config);
            cached = report;
            cachedFingerprint = fingerprint;
            if (report.available()) {
                log.info("tshark 装配完成: {} (来源={}, 版本={})",
                        report.executable(), report.source(), report.version());
            } else {
                log.warn("tshark 装配失败，已尝试 {} 级", report.attempts().size());
                log.warn(report.describe());
            }
            return report;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从轮询目录环境配置装配 tshark。
     *
     * @param environment 环境配置，可为空
     * @return 装配结果
     */
    @Nonnull
    public TsharkProvisioningReport provision(@Nullable DirectoryPollerEnvironment environment) {
        return provision(TsharkCliConfig.from(environment));
    }

    /**
     * 装配 tshark 并要求必须成功。
     *
     * @param config 配置快照
     * @return tshark 可执行文件路径
     * @throws IllegalStateException 装配失败时抛出，异常消息含人工安装指引
     */
    @Nonnull
    public Path require(@Nonnull TsharkCliConfig config) {
        TsharkProvisioningReport report = provision(config);
        if (!report.available()) {
            throw new IllegalStateException(report.describe());
        }
        return report.executable();
    }

    /**
     * 清除装配缓存，下次调用重新走三级策略。
     */
    public void invalidate() {
        lock.lock();
        try {
            cached = null;
            cachedFingerprint = null;
            ExecutableLocator.clearCache();
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 依次执行三级策略。
     *
     * @param config 配置快照
     * @return 装配结果
     */
    @Nonnull
    private TsharkProvisioningReport doProvision(@Nonnull TsharkCliConfig config) {
        List<TsharkProvisioningReport.Attempt> attempts = new ArrayList<>();
        List<TsharkCliConfig.Stage> stages = config.stages();

        if (stages.contains(TsharkCliConfig.Stage.LOCATE)) {
            Optional<Located> located = tryLocate(config, attempts);
            if (located.isPresent()) {
                Path executable = located.get().path();
                return TsharkProvisioningReport.success(
                        sourceOf(config, executable), executable,
                        located.get().version(), attempts);
            }
        }
        if (!config.autoInstall()) {
            return TsharkProvisioningReport.failure(attempts, manualGuidance(config));
        }

        if (stages.contains(TsharkCliConfig.Stage.PACKAGE_MANAGER) && tryPackageManager(config, attempts)) {
            Optional<Located> afterPm = tryLocate(config, attempts);
            if (afterPm.isPresent()) {
                Path executable = afterPm.get().path();
                return TsharkProvisioningReport.success(TsharkProvisioningReport.Source.PACKAGE_MANAGER,
                        executable, afterPm.get().version(), attempts);
            }
        }

        if (stages.contains(TsharkCliConfig.Stage.DOWNLOAD)) {
            Optional<Path> downloaded = tryDownload(config, attempts);
            if (downloaded.isPresent()) {
                return TsharkProvisioningReport.success(TsharkProvisioningReport.Source.DOWNLOADED,
                        downloaded.get(), probeVersion(downloaded.get(), config), attempts);
            }
        } else {
            attempts.add(new TsharkProvisioningReport.Attempt("下载", "已跳过", false,
                    "装配级别中未包含 download"));
        }
        return TsharkProvisioningReport.failure(attempts, manualGuidance(config));
    }

    /**
     * 第一级：定位已安装的 tshark。
     *
     * @param config   配置快照
     * @param attempts 过程记录
     * @return 可执行文件路径
     */
    /**
     * 定位结果：可执行文件路径与探测到的版本。
     *
     * <p>把版本随路径一起返回，避免调用方二次探测：{@code tshark --version} 在 Windows 上
     * 启动 DLL 需要数秒，重复探测会让每次装配白白多花几秒。</p>
     *
     * @param path    可执行文件路径
     * @param version 版本文本，探测失败为 {@code null}
     */
    private record Located(Path path, String version) {
    }

    @Nonnull
    private Optional<Located> tryLocate(@Nonnull TsharkCliConfig config,
                                        @Nonnull List<TsharkProvisioningReport.Attempt> attempts) {
        TsharkTool tool = new TsharkTool();
        if (config.binary() != null) {
            tool.withExecutablePath(config.binary());
        }
        Optional<Path> found = tool.locate();
        if (found.isEmpty()) {
            // 系统与显式路径都找不到时，再看本模块自己管理的安装目录。
            // 少了这一步会出现严重后果：本模块把 tshark 装到 ~/.chua/tshark/<版本>，
            // 而 ExecutableLocator 只搜 PATH 与常见安装目录，不含该目录，
            // 于是每次进程启动都判定"未安装"并重新下载上百 MB。
            found = findManagedInstall(config, attempts);
            if (found.isEmpty()) {
                attempts.add(new TsharkProvisioningReport.Attempt("定位",
                        config.binary() == null ? "PATH、常见安装目录与托管目录" : config.binary(),
                        false, "未找到可执行文件"));
                return Optional.empty();
            }
        }
        CliVersion version = tool.version();
        if (version.isUnknown()) {
            // 能定位但版本探测失败：仍然可用，只是无法做最低版本校验
            attempts.add(new TsharkProvisioningReport.Attempt("定位", found.get().toString(), true,
                    "已定位，版本探测失败"));
            return Optional.of(new Located(found.get(), null));
        }
        attempts.add(new TsharkProvisioningReport.Attempt("定位", found.get().toString(), true,
                "版本 " + version.raw()));
        return Optional.of(new Located(found.get(), version.raw()));
    }

    /**
     * 在本模块管理的安装目录中查找 tshark。
     *
     * <p>先看配置指定版本对应的目录，未命中再遍历安装根目录下的各个版本子目录，
     * 取版本号最大的一个，以便升级后自动使用新版本。</p>
     *
     * <p>注意：Javadoc 里不要写形如 {@code installDir/<任意字符>} 的示例，
     * 其中的 {@code /} 与 {@code *} 可能相邻成 {@code /}{@code *} 而提前结束块注释，
     * 使后续内容被当成代码解析。</p>
     *
     * @param config   配置快照
     * @param attempts 过程记录
     * @return 可执行文件路径
     */
    @Nonnull
    private Optional<Path> findManagedInstall(@Nonnull TsharkCliConfig config,
                                             @Nonnull List<TsharkProvisioningReport.Attempt> attempts) {
        String executableName = TsharkArtifactResolver.executableName();
        Path root = config.installDir();

        Path exact = root.resolve(config.version()).resolve(executableName);
        if (Files.isRegularFile(exact)) {
            attempts.add(new TsharkProvisioningReport.Attempt("定位", exact.toString(), true,
                    "命中托管安装目录"));
            return Optional.of(exact);
        }
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path best = null;
        String bestVersion = null;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path candidate = child.resolve(executableName);
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String version = child.getFileName().toString();
                if (best == null || compareVersion(version, bestVersion) > 0) {
                    best = candidate;
                    bestVersion = version;
                }
            }
        } catch (IOException e) {
            log.debug("遍历托管安装目录失败: {}", root, e);
            return Optional.empty();
        }
        if (best == null) {
            return Optional.empty();
        }
        attempts.add(new TsharkProvisioningReport.Attempt("定位", best.toString(), true,
                "命中托管安装目录，版本 " + bestVersion));
        return Optional.of(best);
    }

    /**
     * 比较两个版本号文本，非法版本按字典序排在后面。
     *
     * @param left  左值
     * @param right 右值
     * @return 左值更小返回负数
     */
    private static int compareVersion(@Nonnull String left, @Nonnull String right) {
        int[] leftParts = parseVersion(left);
        int[] rightParts = parseVersion(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int a = i < leftParts.length ? leftParts[i] : 0;
            int b = i < rightParts.length ? rightParts[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        // 版本号相同则按字符串长度区分，避免非法版本号被当成更高版本
        return Integer.compare(left.length(), right.length());
    }

    /**
     * 把版本号文本拆成数字段，无法解析的段按 -1 处理。
     *
     * @param version 版本号文本
     * @return 数字段
     */
    @Nonnull
    private static int[] parseVersion(@Nonnull String version) {
        String[] tokens = version.split("[.\\-_+]");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i]);
            } catch (NumberFormatException e) {
                parts[i] = -1;
            }
        }
        return parts;
    }

    /**
     * 第二级：用系统包管理器安装。
     *
     * @param config   配置快照
     * @param attempts 过程记录
     * @return 是否安装成功
     */
    private boolean tryPackageManager(@Nonnull TsharkCliConfig config,
                                      @Nonnull List<TsharkProvisioningReport.Attempt> attempts) {
        List<PackageManager.Type> available = PackageManager.detect();
        if (available.isEmpty()) {
            attempts.add(new TsharkProvisioningReport.Attempt("包管理器", "无", false, "未检测到可用的包管理器"));
            return false;
        }
        String configured = config.packageManager();
        for (PackageManager.Type type : available) {
            if (configured != null && !type.getCommand().equalsIgnoreCase(configured)) {
                continue;
            }
            String packageId = packageIdFor(type);
            attempts.add(new TsharkProvisioningReport.Attempt("包管理器",
                    type.getCommand() + " " + packageId, true, "开始安装"));
            CmdResult result = PackageManager.installWith(type, packageId);
            if (result.isSuccess()) {
                ExecutableLocator.clearCache();
                attempts.add(new TsharkProvisioningReport.Attempt("包管理器",
                        type.getCommand() + " " + packageId, true, "安装成功"));
                return true;
            }
            attempts.add(new TsharkProvisioningReport.Attempt("包管理器",
                    type.getCommand() + " " + packageId, false, "安装失败，退出码 " + result.getExitCode()));
        }
        return false;
    }

    /**
     * 取各包管理器下的安装包 ID。
     *
     * <p>不能沿用一个统一 ID：各生态的包名与标识规则不同，用错 ID 会静默走到
     * 另一个包或直接失败——实测 {@code winget install --id wireshark} 会解析到
     * Wireshark 官方的 MSI 安装包（依赖 Npcap 驱动、需管理员权限），
     * 而不是本模块想要的免驱动命令行工具。</p>
     *
     * <p>逐个平台的实际包名：</p>
     * <ul>
     *   <li>winget — 必须用完整包标识 {@code WiresharkFoundation.Wireshark}，
     *       短名会匹配不到或匹配到错误的包；</li>
     *   <li>choco — {@code wireshark}；</li>
     *   <li>brew — Wireshark 是 cask 而非 formula，需 {@code --cask}；
     *       {@link PackageManager} 的模板不支持该开关，
     *       因此这里只尝试 {@code wireshark}，失败后由第三级下载兜底；</li>
     *   <li>apt / yum / dnf — Debian 系把 Wireshark 拆成多个包，
     *       含 tshark 的包名是 {@code tshark}；</li>
     *   <li>apk — {@code wireshark}。</li>
     * </ul>
     *
     * @param type 包管理器类型
     * @return 安装包 ID
     */
    @Nonnull
    private String packageIdFor(@Nonnull PackageManager.Type type) {
        return switch (type) {
            case WINGET -> "WiresharkFoundation.Wireshark";
            case APT, YUM, DNF -> "tshark";
            case CHOCO, BREW, APK -> "wireshark";
        };
    }

    /**
     * 第三级：下载并安装官方制品。
     *
     * @param config   配置快照
     * @param attempts 过程记录
     * @return 可执行文件路径
     */
    @Nonnull
    private Optional<Path> tryDownload(@Nonnull TsharkCliConfig config,
                                       @Nonnull List<TsharkProvisioningReport.Attempt> attempts) {
        List<TsharkArtifact> artifacts = TsharkArtifactResolver.resolve(config);
        if (artifacts.isEmpty()) {
            attempts.add(new TsharkProvisioningReport.Attempt("下载", "无", false,
                    "当前平台无官方二进制包（类 Unix 请使用包管理器），或未配置下载地址"));
            return Optional.empty();
        }
        for (TsharkArtifact artifact : artifacts) {
            long started = System.nanoTime();
            try {
                Path executable = TsharkArtifactInstaller.install(artifact, config);
                long costMillis = (System.nanoTime() - started) / 1_000_000L;
                attempts.add(new TsharkProvisioningReport.Attempt("下载", artifact.url(), true,
                        "安装成功，耗时 " + costMillis + "ms"));
                return Optional.of(executable);
            } catch (TsharkArtifactInstaller.InstallException e) {
                long costMillis = (System.nanoTime() - started) / 1_000_000L;
                attempts.add(new TsharkProvisioningReport.Attempt("下载", artifact.url(), false,
                        e.getMessage() + "（耗时 " + costMillis + "ms）"));
                log.debug("制品安装失败，尝试下一个: {}", artifact.url(), e);
            }
        }
        return Optional.empty();
    }

    /**
     * 探测已定位到的 tshark 版本。
     *
     * @param executable 可执行文件路径
     * @param config     配置快照
     * @return 版本字符串，探测失败返回 {@code null}
     */
    @Nullable
    private String probeVersion(@Nonnull Path executable, @Nonnull TsharkCliConfig config) {
        TsharkTool tool = new TsharkTool();
        tool.withExecutablePath(executable.toString());
        CliVersion version = tool.version();
        return version.isUnknown() ? null : version.raw();
    }

    /**
     * 判断来源层级：显式路径优先于自动定位。
     *
     * @param config     配置快照
     * @param executable 可执行文件路径
     * @return 来源层级
     */
    @Nonnull
    private TsharkProvisioningReport.Source sourceOf(@Nonnull TsharkCliConfig config, @Nonnull Path executable) {
        if (config.binary() != null) {
            return TsharkProvisioningReport.Source.EXPLICIT;
        }
        String env = System.getenv("TSHARK_BIN");
        return env != null && env.equalsIgnoreCase(executable.toString())
                ? TsharkProvisioningReport.Source.EXPLICIT
                : TsharkProvisioningReport.Source.LOCATED;
    }

    /**
     * 生成配置指纹，用于判断缓存是否仍然有效。
     *
     * @param config 配置快照
     * @return 指纹
     */
    @Nonnull
    private String fingerprint(@Nonnull TsharkCliConfig config) {
        return String.join("|",
                String.valueOf(config.binary()),
                String.valueOf(config.autoInstall()),
                config.installDir().toString(),
                config.version(),
                String.valueOf(config.explicitUrl()),
                String.valueOf(config.sha256()),
                config.mirrors().toString(),
                config.stages().toString(),
                String.valueOf(config.packageManager()));
    }

    /**
     * 生成人工安装指引。
     *
     * <p>三级策略全部失败时，除了报错还必须给出"接下来怎么办"，
     * 否则调用方只能看到一句找不到 tshark。指引内容随平台与可用工具变化。</p>
     *
     * @param config 配置快照
     * @return 指引文本
     */
    @Nonnull
    private String manualGuidance(@Nonnull TsharkCliConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("tshark 自动装配失败，可按下列方式之一处理：");
        sb.append(System.lineSeparator()).append("  1) 手动安装 Wireshark（含 tshark）后重试；");
        if (TsharkArtifactResolver.isWindows()) {
            sb.append(System.lineSeparator()).append(
                    "     下载页: https://www.wireshark.org/download.html （Windows 安装包需管理员权限）");
            sb.append(System.lineSeparator()).append(
                    "     Windows 实时抓包还需安装 Npcap 驱动: https://npcap.com/");
        } else if (TsharkArtifactResolver.isMacOs()) {
            sb.append(System.lineSeparator()).append("     brew install --cask wireshark");
        } else {
            sb.append(System.lineSeparator()).append("     apt-get install -y tshark   # 或 dnf install -y wireshark-cli");
            sb.append(System.lineSeparator()).append(
                    "     实时抓包需要 root 或授予 CAP_NET_RAW/CAP_NET_ADMIN 能力");
        }
        sb.append(System.lineSeparator()).append(
                "  2) 指定已安装的可执行文件: -D" + TsharkSettings.KEY_BINARY + "=/path/to/tshark"
                        + " 或设置环境变量 TSHARK_BIN");
        sb.append(System.lineSeparator()).append(
                "  3) 使用自定义镜像或离线包: -D" + TsharkSettings.KEY_DOWNLOAD_MIRRORS
                        + "=https://your-mirror/wireshark 或 -D" + TsharkSettings.KEY_DOWNLOAD_URL
                        + "=https://your-mirror/tshark-portable.zip");
        sb.append(System.lineSeparator()).append(
                "  4) 调整各级超时后重试: " + String.join("、",
                        TsharkSettings.KEY_DOWNLOAD_TIMEOUT_SECONDS, TsharkSettings.KEY_INSTALL_TIMEOUT_SECONDS)
                        + "（当前下载 " + config.downloadTimeoutSeconds() + "s，安装 "
                        + config.installTimeoutSeconds() + "s）");
        sb.append(System.lineSeparator()).append(
                "  5) 关闭自动装配仅使用已安装版本: -D" + TsharkSettings.KEY_AUTO_INSTALL + "=false");
        sb.append(System.lineSeparator()).append(
                "  6) 跳过耗时的包管理器安装（已配好镜像时更快）: -D" + TsharkSettings.KEY_STAGES
                + "=locate,download");
        return sb.toString();
    }

    /**
     * 列出网卡，供抓包会话选择接口，等价于 {@code tshark -D}。
     *
     * <p>网卡名因平台而异（Windows 为数字编号，Linux 为 {@code eth0} 等），
     * 因此必须通过 tshark 自身枚举，不能用 JDK 的
     * {@code NetworkInterface} 名称替代。</p>
     *
     * @param config 配置快照
     * @return 原始输出行
     * @throws IllegalStateException tshark 不可用或执行失败时抛出
     */
    @Nonnull
    public List<String> listInterfaces(@Nonnull TsharkCliConfig config) {
        TsharkProvisioningReport report = provision(config);
        if (!report.available()) {
            throw new IllegalStateException(report.describe());
        }
        TsharkTool tool = new TsharkTool();
        tool.withExecutablePath(report.executable().toString());
        CmdResult result = tool.execute(config.execTimeoutSeconds(), TimeUnit.SECONDS, "-D");
        if (!result.isSuccess()) {
            throw new IllegalStateException("执行 tshark -D 失败，退出码 " + result.getExitCode()
                    + ": " + result.getStderr());
        }
        List<String> lines = new ArrayList<>();
        for (String line : result.getStdout().split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return List.copyOf(lines);
    }

    /**
     * 返回 tshark 所在平台描述，供诊断输出。
     *
     * @return 平台描述
     */
    @Nonnull
    public String platform() {
        return System.getProperty("os.name", "unknown") + "/" + System.getProperty("os.arch", "unknown")
                + " (" + TsharkArtifactResolver.architecture().toLowerCase(Locale.ROOT) + ")";
    }
}
