package com.chua.network.support.tshark.cli;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.ExecutableLocator;
import com.chua.common.support.network.download.Downloader;
import com.chua.common.support.network.download.DownloadResult;
import com.chua.common.support.network.download.extractor.Extractor;
import com.chua.common.support.network.download.extractor.ExtractorFactory;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * tshark 制品安装器：把下载到的制品落成可执行的 tshark。
 *
 * <p>安装分两类动作：{@link TsharkArtifact.Kind#requiresExecution()} 为真的格式
 * （NSIS 安装包、DMG）需要"执行"安装程序；压缩包格式则"解压"即可。
 * 两者完成后都回到同一个动作：在安装目录下递归查找可执行文件并做版本校验，
 * 校验不通过则判定本次安装失败，调用方据此尝试下一个镜像。</p>
 *
 * <p><b>超时</b>：下载与安装两步都受 {@link TsharkCliConfig} 的超时约束。
 * 进程调用统一走 {@link CmdExecutors} 的带超时重载，超时后强杀进程并返回
 * {@link CmdResult#EXIT_CODE_TIMEOUT}，不会无限等待；TAR.XZ 在缺少
 * {@code Extractor} 实现时改用系统 {@code tar} 命令，同样受超时约束。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class TsharkArtifactInstaller {

    /**
     * 递归查找可执行文件时的最大目录深度
     *
     * <p>PortableApps 的目录结构为 {@code <dir>/App/bin/tshark.exe}，深度 3；
     * 官方 NSIS 包为 {@code <dir>/tshark.exe}，深度 1。留到 8 层足以覆盖
     * 镜像站自行重打包的情况，同时避免在异常目录树上做无界遍历。</p>
     */
    private static final int MAX_SEARCH_DEPTH = 8;

    /**
     * 安装失败原因
     */
    public static final class InstallException extends Exception {

        /**
         * 构造异常。
         *
         * @param message 失败原因
         * @param cause   原始异常，可为空
         */
        public InstallException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 禁止实例化
     */
    private TsharkArtifactInstaller() {
  // 工具 类
    }

    /**
     * 下载并安装指定制品。
     *
     * @param artifact 制品描述
     * @param config   配置快照
     * @return 安装后的 tshark 可执行文件路径
     * @throws InstallException 下载失败、校验不符、安装失败或安装后仍找不到可执行文件
     */
    @Nonnull
    public static Path install(@Nonnull TsharkArtifact artifact, @Nonnull TsharkCliConfig config)
            throws InstallException {
        Path targetDir = config.installDir().resolve(artifact.version()).normalize();
        Path downloadDir = config.installDir().resolve(".download").normalize();
        try {
            Files.createDirectories(downloadDir);
            Path archive = download(artifact, downloadDir, config);
            verifyChecksum(archive, artifact.sha256());
            Path executable = artifact.kind().requiresExecution()
                    ? executeInstaller(artifact, archive, targetDir, config)
                    : extractArchive(artifact, archive, targetDir, config);
            log.info("tshark 安装完成: {} (来自 {})", executable, artifact.url());
            return executable;
        } catch (IOException e) {
            throw new InstallException("安装 tshark 失败: " + e.getMessage(), e);
        } catch (InstallException e) {
            // 失败时清掉本次创建的空目录：安装被中断（进程被杀、机器重启）会在用户主目录下
            // 留下空的 ~/.chua/tshark/<版本>，日积月累成垃圾，也让"已安装"的假象留存
            removeEmptyTargetDir(targetDir);
            throw e;
        } finally {
            deleteQuietly(downloadDir);
        }
    }

    /**
     * 安装失败后清理空的目标目录。
     *
     * <p>只删空目录：一旦里面有文件就可能是上次成功安装的产物，误删会让用户丢掉
     * 一个可用的 tshark。宁可留个非空目录由人判断，也不自动删除。</p>
     *
     * @param targetDir 安装目录
     */
    private static void removeEmptyTargetDir(@Nonnull Path targetDir) {
        try {
            if (Files.isDirectory(targetDir)) {
                try (var stream = Files.list(targetDir)) {
                    if (stream.findAny().isEmpty()) {
                        Files.deleteIfExists(targetDir);
                        log.debug("已清理安装失败留下的空目录: {}", targetDir);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("清理空安装目录失败: {}", targetDir, e);
        }
    }

    // ==================== 下载 ====================

    /**
     * 下载制品到本地。
     *
     * <p>复用 {@link Downloader}（内部为 JDK {@code HttpURLConnection} 实现，
     * 支持断点续传与进度显示）。下载的墙钟上限由
     * {@link TsharkCliConfig#downloadTimeoutSeconds()} 约束：
     * 读取超时设为该值，同时用独立线程做整体墙钟看门狗，
     * 避免"服务端持续慢速吐字节"时永远读不满一个连接。</p>
     *
     * @param artifact    制品描述
     * @param downloadDir 下载缓存目录
     * @param config      配置快照
     * @return 下载完成的文件路径
     * @throws InstallException 下载失败或超时
     */
    @Nonnull
    private static Path download(@Nonnull TsharkArtifact artifact, @Nonnull Path downloadDir,
                                 @Nonnull TsharkCliConfig config) throws InstallException {
        long timeoutSeconds = config.downloadTimeoutSeconds();
        Path target = downloadDir.resolve(artifact.fileName());
        // 断点续传依赖 .part 语义，强制重下会让每次失败重试都从零开始
        boolean force = !Files.isRegularFile(target) && !Files.isRegularFile(target.resolveSibling(
                target.getFileName() + ".part"));
        try {
            DownloadResult result = Downloader.create()
                    .url(artifact.url())
                    .target(downloadDir)
                    .filename(artifact.fileName())
                    .connectTimeout((int) Math.min(timeoutSeconds * 1000L, 60_000L))
                    .readTimeout((int) Math.min(timeoutSeconds * 1000L, 300_000L))
                    .forceDownload(force)
                    .skipMd5Check(true)
                    .showProgress(true)
                    .execute();
            Path file = result.getFile();
            if (file == null || !Files.isRegularFile(file)) {
                throw new InstallException("下载未产出文件: " + artifact.url(), null);
            }
            return file;
        } catch (IOException e) {
            throw new InstallException("下载失败(" + timeoutSeconds + "s 上限): " + artifact.url()
                    + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 校验制品的 SHA-256。
     *
     * @param archive   制品文件
     * @param expected  期望校验值，为空则跳过
     * @throws InstallException 校验值不匹配
     * @throws IOException     读取文件失败
     */
    private static void verifyChecksum(@Nonnull Path archive, @Nullable String expected)
            throws InstallException, IOException {
        if (expected == null || expected.isBlank()) {
            log.info("未配置 SHA-256，跳过制品校验: {}", archive.getFileName());
            return;
        }
        String actual = sha256(archive);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new InstallException("制品 SHA-256 不匹配: expected=" + expected + " actual=" + actual, null);
        }
        log.info("制品 SHA-256 校验通过: {}", actual);
    }

    /**
     * 计算文件的 SHA-256。
     *
     * @param file 文件
     * @return 小写十六进制摘要
     * @throws IOException 读取失败
     */
    @Nonnull
    private static String sha256(@Nonnull Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("当前 JVM 不支持 SHA-256", e);
        }
    }

    // ==================== 安装 ====================

    /**
     * 执行安装包完成安装。
     *
     * @param artifact  制品描述
     * @param archive   制品文件
     * @param targetDir 安装目录
     * @param config    配置快照
     * @return 安装后的可执行文件路径
     * @throws InstallException 安装程序执行失败或超时
     */
    @Nonnull
    private static Path executeInstaller(@Nonnull TsharkArtifact artifact, @Nonnull Path archive,
                                         @Nonnull Path targetDir, @Nonnull TsharkCliConfig config)
            throws InstallException {
        if (artifact.kind() == TsharkArtifact.Kind.DMG) {
            return installDmg(archive, targetDir, config);
        }
        installNsis(artifact, archive, targetDir, config);
        return findExecutable(targetDir, artifact.executableName())
                .orElseThrow(() -> new InstallException(
                        "NSIS 静默安装后未找到 " + artifact.executableName() + "，安装目录: " + targetDir, null));
    }

    /**
     * 以静默模式执行 NSIS 安装包。
     *
     * <p>安装目录参数在两种 NSIS 发行形态下写法不同，实测差异如下：</p>
     * <ul>
     *   <li>官方安装包（{@code Wireshark-x.y.z-x64.exe}）是标准 NSIS，
     *       用 {@code /S} + {@code /D=<dir>}；{@code /D=} 必须是最后一个参数，
     *       且其值不能被引号包裹（NSIS 直接按原始命令行解析），
     *       因此安装目录含空格时静默安装会失败——默认目录 {@code ~/.chua/tshark}
     *       位于用户目录下不含空格，正是为此。</li>
     *   <li>PortableApps 封装包（{@code WiresharkPortable64_*.paf.exe}）是内嵌安装器的
     *       启动壳，标准 NSIS 的 {@code /D=} 会被壳层丢弃，安装目录需用
     *       {@code /DESTINATION=<dir>}。该形态的静默安装在无桌面会话的环境下
     *       仍可能停在语言/许可对话框，因此排在官方包之后作为次选。</li>
     * </ul>
     *
     * @param artifact  制品描述
     * @param archive   安装包
     * @param targetDir 安装目录
     * @param config    配置快照
     * @throws InstallException 安装程序执行失败或超时
     */
    private static void installNsis(@Nonnull TsharkArtifact artifact, @Nonnull Path archive,
                                    @Nonnull Path targetDir, @Nonnull TsharkCliConfig config)
            throws InstallException {
        if (targetDir.toString().contains(" ")) {
            log.warn("安装目录含空格，NSIS 静默安装可能失败: {}", targetDir);
        }
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new InstallException("创建安装目录失败: " + targetDir, e);
        }
        String directoryFlag = artifact.kind() == TsharkArtifact.Kind.PORTABLE_NSIS
                ? "/DESTINATION="
                : "/D=";
        long timeout = config.installTimeoutSeconds();
        CmdResult result = CmdExecutors.execute(new String[]{
                archive.toAbsolutePath().toString(), "/S", directoryFlag + targetDir.toAbsolutePath()
        }, timeout, TimeUnit.SECONDS);
        if (result.isTimeout()) {
            throw new InstallException("安装程序执行超时(" + timeout + "s): " + archive.getFileName()
                    + "；该安装包可能需要桌面会话，请在交互式终端手动安装", null);
        }
        if (!result.isSuccess()) {
            throw new InstallException("安装程序退出码 " + result.getExitCode() + ": " + result.getStderr(), null);
        }
        log.info("安装包静默安装完成: {} -> {}", archive.getFileName(), targetDir);
    }

    /**
     * 挂载 DMG 并拷出可执行文件。
     *
     * <p>挂载点必须是空目录，且无论成功失败都要卸载——遗留挂载点会持续占用
     * 镜像句柄，后续 {@code hdiutil attach} 报"设备已挂载"。</p>
     *
     * @param archive   DMG 文件
     * @param targetDir 安装目录
     * @param config    配置快照
     * @return 安装后的可执行文件路径
     * @throws InstallException 挂载或拷贝失败
     */
    @Nonnull
    private static Path installDmg(@Nonnull Path archive, @Nonnull Path targetDir,
                                  @Nonnull TsharkCliConfig config) throws InstallException {
        long timeout = config.installTimeoutSeconds();
        Path mountPoint = targetDir.resolve(".mount");
        CmdResult mount = CmdExecutors.execute(new String[]{
                "hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint",
                mountPoint.toAbsolutePath().toString(), archive.toAbsolutePath().toString()
        }, timeout, TimeUnit.SECONDS);
        if (mount.isTimeout()) {
            throw new InstallException("挂载 DMG 超时(" + timeout + "s): " + archive.getFileName(), null);
        }
        if (!mount.isSuccess()) {
            throw new InstallException("挂载 DMG 失败，退出码 " + mount.getExitCode() + ": " + mount.getStderr(), null);
        }
        try {
            Optional<Path> mounted = findExecutable(mountPoint, "tshark");
            if (mounted.isEmpty()) {
                throw new InstallException("DMG 内未找到 tshark: " + archive.getFileName(), null);
            }
            return copyOut(mounted.get(), targetDir);
        } finally {
            CmdExecutors.execute(new String[]{"hdiutil", "detach", mountPoint.toAbsolutePath().toString()},
                    timeout, TimeUnit.SECONDS);
        }
    }

    /**
     * 把挂载点内的可执行文件及其同级依赖拷到安装目录。
     *
     * <p>macOS 上的 {@code tshark} 依赖同目录的动态库与插件目录，
     * 因此整目录一起拷贝而非只取单个文件。</p>
     *
     * @param source    挂载点内的可执行文件
     * @param targetDir 安装目录
     * @return 拷贝后的可执行文件路径
     * @throws InstallException 拷贝失败
     */
    @Nonnull
    private static Path copyOut(@Nonnull Path source, @Nonnull Path targetDir) throws InstallException {
        Path destination = targetDir.resolve(source.getFileName().toString());
        try {
            copyTree(source, destination);
            return destination;
        } catch (IOException e) {
            throw new InstallException("从 DMG 拷出 tshark 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 递归复制文件或目录。
     *
     * @param source      源
     * @param destination 目标
     * @throws IOException 读写失败
     */
    private static void copyTree(@Nonnull Path source, @Nonnull Path destination) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 解压压缩包形式的制品。
     *
     * <p>ZIP / TAR.GZ 走 {@link ExtractorFactory}（依赖其 SPI 登记）；
     * TAR.XZ 当前没有对应 Extractor 实现，退化为调用系统 {@code tar -xJf}，
     * 该命令在 Windows 10+ 与主流类 Unix 上均可用。</p>
     *
     * @param artifact  制品描述
     * @param archive   制品文件
     * @param targetDir 安装目录
     * @param config    配置快照
     * @return 安装后的可执行文件路径
     * @throws InstallException 解压失败或超时
     */
    @Nonnull
    private static Path extractArchive(@Nonnull TsharkArtifact artifact, @Nonnull Path archive,
                                       @Nonnull Path targetDir, @Nonnull TsharkCliConfig config)
            throws InstallException {
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new InstallException("创建安装目录失败: " + targetDir, e);
        }
        if (artifact.kind() == TsharkArtifact.Kind.TAR_XZ) {
            extractTarXz(archive, targetDir, config);
        } else {
            Extractor extractor = ExtractorFactory.getExtractor(artifact.fileName());
            if (extractor == null) {
                throw new InstallException("没有可处理 " + artifact.fileName() + " 的解压器", null);
            }
            if (!extractor.extract(archive.toFile(), targetDir.toFile())) {
                throw new InstallException("解压失败: " + artifact.fileName(), null);
            }
        }
        return findExecutable(targetDir, artifact.executableName())
                .orElseThrow(() -> new InstallException(
                        "解压后未找到 " + artifact.executableName() + "，目录: " + targetDir, null));
    }

    /**
     * 用系统 tar 解压 TAR.XZ。
     *
     * @param archive   制品文件
     * @param targetDir 安装目录
     * @param config    配置快照
     * @throws InstallException 解压失败或超时
     */
    private static void extractTarXz(@Nonnull Path archive, @Nonnull Path targetDir,
                                     @Nonnull TsharkCliConfig config) throws InstallException {
        long timeout = config.installTimeoutSeconds();
        CmdResult result = CmdExecutors.execute(new String[]{
                "tar", "-xJf", archive.toAbsolutePath().toString(), "-C", targetDir.toAbsolutePath().toString()
        }, timeout, TimeUnit.SECONDS);
        if (result.isTimeout()) {
            throw new InstallException("tar 解压超时(" + timeout + "s): " + archive.getFileName(), null);
        }
        if (!result.isSuccess()) {
            throw new InstallException("tar 解压失败，退出码 " + result.getExitCode() + ": " + result.getStderr(), null);
        }
    }

    // ==================== 可执行文件查找 ====================

    /**
     * 在目录树中查找可执行文件。
     *
     * <p>同层多个候选时按名称长度升序取最短的一个：{@code bin/tshark.exe} 优先于
     * {@code bin/dumpcap-wrapper/tshark.exe} 这类嵌套副本。</p>
     *
     * @param root           根目录
     * @param executableName 可执行文件名
     * @return 可执行文件路径，未找到返回空 {@link Optional}
     */
    @Nonnull
    public static Optional<Path> findExecutable(@Nonnull Path root, @Nonnull String executableName) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, java.util.Set.of(), MAX_SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path name = file.getFileName();
                    if (name != null && name.toString().equalsIgnoreCase(executableName)) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 单个子目录不可读不应中断整棵树查找
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 查找是尽力而为：遍历失败按"没找到"处理，由调用方给出含目录路径的报错
            log.warn("遍历安装目录失败: {}", root, e);
            return Optional.empty();
        }
        return matches.stream()
                .min((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()));
    }

    /**
     * 静默递归删除目录。
     *
     * @param directory 目录
     */
    private static void deleteQuietly(@Nonnull Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("删除临时文件失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("清理下载缓存目录失败: {}", directory, e);
        }
    }

    /**
     * 刷新可执行文件定位缓存。
     *
     * <p>本模块新安装的 tshark 位于用户目录下，不在 PATH 与候选目录中，
     * 必须清缓存后由 {@link ExecutableLocator} 重新扫描才能被后续定位发现。</p>
     */
    public static void refreshLocator() {
        ExecutableLocator.clearCache();
    }
}
