package com.chua.network.support.tshark.cli;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * tshark 下载制品解析器：按当前平台把版本号与镜像基址拼成可下载的地址。
 *
 * <p>各平台的官方发布形态不同，可用的兜底方式也不同，因此解析分三条路径：</p>
 * <table border="1">
 *   <caption>平台与制品形态</caption>
 *   <tr><th>平台</th><th>制品</th><th>说明</th></tr>
 *   <tr><td>Windows amd64</td><td>PortableApps 便携版</td>
 *       <td>免管理员、可装到任意目录，是首选</td></tr>
 *   <tr><td>Windows arm64</td><td>官方安装包</td><td>官方未发布 arm64 便携版</td></tr>
 *   <tr><td>macOS</td><td>DMG 磁盘映像</td><td>需挂载后拷出 {@code .app}</td></tr>
 *   <tr><td>类 Unix</td><td>无</td>
 *       <td>官方不发布二进制包，一律走包管理器；仅当显式配置下载地址时才走下载</td></tr>
 * </table>
 *
 * <p>所有平台的地址都返回<b>多个候选</b>而不是单个：单个镜像不可达是常态
 * （镜像站按地域分流，且部分镜像会限速），逐个尝试并以"下一个镜像"作为失败降级。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class TsharkArtifactResolver {

    /**
     * Windows 下载目录
     */
    private static final String WINDOWS_DIR = "win64";

    /**
     * macOS 下载目录
     */
    private static final String MACOS_DIR = "osx";

    /**
     * macOS 官方文件名中的空格，需要 URL 编码
     */
    private static final String MACOS_FILE_SEPARATOR = "%20";

    /**
     * 禁止实例化
     */
    private TsharkArtifactResolver() {
  // 工具 类
    }

    /**
     * 解析当前平台下可尝试的下载制品。
     *
     * @param config 配置快照
     * @return 候选制品列表，按尝试顺序排列；无可用制品时返回空列表
     */
    @Nonnull
    public static List<TsharkArtifact> resolve(@Nonnull TsharkCliConfig config) {
        String explicitUrl = config.explicitUrl();
        if (explicitUrl != null) {
            return List.of(fromExplicitUrl(config, explicitUrl));
        }
        String version = normalizeVersion(config.version());
        String executableName = executableName();
        List<String> mirrors = config.mirrors();
        if (mirrors.isEmpty()) {
            return List.of();
        }

        String architecture = architecture();
        if (isWindows()) {
            return resolveWindows(version, executableName, mirrors, architecture, config.sha256());
        }
        if (isMacOs()) {
            return resolveMacOs(version, executableName, mirrors, config.sha256());
        }
        // 类 Unix 无官方二进制包：只有显式配置了下载地址才走下载，否则由包管理器兜底
        log.info("类 Unix 平台无 Wireshark 官方二进制包，跳过下载兜底，交由包管理器安装");
        return List.of();
    }

    /**
     * 解析 Windows 平台制品。
     *
     * @param version        版本号
     * @param executableName 可执行文件名
     * @param mirrors        镜像列表
     * @param architecture   CPU 架构
     * @param sha256         期望校验值
     * @return 候选制品列表
     */
    @Nonnull
    private static List<TsharkArtifact> resolveWindows(String version, String executableName,
                                                       List<String> mirrors, String architecture,
                                                       @Nullable String sha256) {
        List<TsharkArtifact> artifacts = new ArrayList<>();
        if ("386".equals(architecture)) {
            log.warn("不支持的 32 位 Windows 架构: {}", architecture);
            return List.of();
        }
        String archSuffix = "arm64".equals(architecture) ? "arm64" : "x64";
        // 官方安装包在前：它是标准 NSIS，静默安装受官方支持
        appendAll(artifacts, mirrors, mirror -> mirror + "/" + WINDOWS_DIR
                + "/Wireshark-" + version + "-" + archSuffix + ".exe",
                TsharkArtifact.Kind.INSTALLER_NSIS, version, executableName, sha256);
        // PortableApps 便携版在后：它是内嵌安装器的启动壳，静默安装在无桌面会话下不可靠
        if ("amd64".equals(architecture) || "x86_64".equals(architecture) || architecture.isEmpty()) {
            appendAll(artifacts, mirrors, mirror -> mirror + "/" + WINDOWS_DIR
                    + "/WiresharkPortable64_" + version + ".paf.exe",
                    TsharkArtifact.Kind.PORTABLE_NSIS, version, executableName, sha256);
        }
        return List.copyOf(artifacts);
    }

    /**
     * 解析 macOS 平台制品。
     *
     * @param version        版本号
     * @param executableName 可执行文件名
     * @param mirrors        镜像列表
     * @param sha256         期望校验值
     * @return 候选制品列表
     */
    @Nonnull
    private static List<TsharkArtifact> resolveMacOs(String version, String executableName,
                                                     List<String> mirrors, @Nullable String sha256) {
        List<TsharkArtifact> artifacts = new ArrayList<>();
        String fileName = "Wireshark" + MACOS_FILE_SEPARATOR + version + ".dmg";
        appendAll(artifacts, mirrors, mirror -> mirror + "/" + MACOS_DIR + "/" + fileName,
                TsharkArtifact.Kind.DMG, version, executableName, sha256);
        return List.copyOf(artifacts);
    }

    /**
     * 遍历镜像生成候选制品。
     *
     * @param artifacts      收集容器
     * @param mirrors        镜像列表
     * @param pathBuilder    相对地址拼装函数
     * @param kind           制品格式
     * @param version        版本号
     * @param executableName 可执行文件名
     * @param sha256         期望校验值
     */
    private static void appendAll(List<TsharkArtifact> artifacts, List<String> mirrors,
                                  Function<String, String> pathBuilder,
                                  TsharkArtifact.Kind kind, String version,
                                  String executableName, @Nullable String sha256) {
        for (String mirror : mirrors) {
            String url = pathBuilder.apply(mirror);
            artifacts.add(new TsharkArtifact(version, kind, url, fileNameOf(url), sha256, executableName));
        }
    }

    /**
     * 由显式下载地址构造制品，格式按文件名后缀推断。
     *
     * @param config     配置快照
     * @param explicitUrl 下载地址
     * @return 制品描述
     */
    @Nonnull
    private static TsharkArtifact fromExplicitUrl(@Nonnull TsharkCliConfig config, @Nonnull String explicitUrl) {
        String fileName = fileNameOf(explicitUrl);
        return new TsharkArtifact(config.version(), kindOf(fileName), explicitUrl, fileName,
                config.sha256(), executableName());
    }

    /**
     * 按文件名后缀推断制品格式。
     *
     * @param fileName 文件名
     * @return 制品格式，未知后缀按 ZIP 处理
     */
    @Nonnull
    public static TsharkArtifact.Kind kindOf(@Nullable String fileName) {
        if (fileName == null) {
            return TsharkArtifact.Kind.ZIP;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return TsharkArtifact.Kind.TAR_GZ;
        }
        if (lower.endsWith(".tar.xz")) {
            return TsharkArtifact.Kind.TAR_XZ;
        }
        if (lower.endsWith(".zip")) {
            return TsharkArtifact.Kind.ZIP;
        }
        if (lower.endsWith(".dmg")) {
            return TsharkArtifact.Kind.DMG;
        }
        if (lower.endsWith(".paf.exe")) {
            return TsharkArtifact.Kind.PORTABLE_NSIS;
        }
        if (lower.endsWith(".exe")) {
            return TsharkArtifact.Kind.INSTALLER_NSIS;
        }
        return TsharkArtifact.Kind.ZIP;
    }

    /**
     * 取 URL 末段作为文件名，并解码百分号编码。
     *
     * @param url 下载地址
     * @return 文件名
     */
    @Nonnull
    public static String fileNameOf(@Nonnull String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return URLDecoder.decode(name, StandardCharsets.UTF_8);
    }

    /**
     * 归一化版本号，容忍带 {@code v} 前缀或空白。
     *
     * @param version 原始版本号
     * @return 归一后的版本号，非法时回退默认版本
     */
    @Nonnull
    public static String normalizeVersion(@Nullable String version) {
        if (version == null || version.isBlank()) {
            return TsharkSettings.DEFAULT_DOWNLOAD_VERSION;
        }
        String trimmed = version.trim();
        if (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V') {
            trimmed = trimmed.substring(1);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return TsharkSettings.DEFAULT_DOWNLOAD_VERSION;
            }
        }
        return trimmed.isEmpty() ? TsharkSettings.DEFAULT_DOWNLOAD_VERSION : trimmed;
    }

    /**
     * 当前平台的可执行文件名。
     *
     * @return Windows 返回 {@code tshark.exe}，其余返回 {@code tshark}
     */
    @Nonnull
    public static String executableName() {
        return isWindows() ? "tshark.exe" : "tshark";
    }

    /**
     * 当前 CPU 架构，归一化为 {@code amd64} / {@code arm64} / {@code 386}。
     *
     * @return 归一化架构
     */
    @Nonnull
    public static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("64")) {
            return "amd64";
        }
        if (arch.contains("86")) {
            return "386";
        }
        return arch;
    }

    /**
     * 是否为 Windows 平台。
     *
     * @return 是 Windows 返回 true
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 是否为 macOS 平台。
     *
     * @return 是 macOS 返回 true
     */
    public static boolean isMacOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return name.contains("mac") || name.contains("darwin");
    }
}
