package com.chua.deeplearning.support.engine;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 通用外部 CLI 模型运行器。
 *
 * <p>对"模型 = 可执行 CLI（自下载/自管模型文件）"类引擎提供统一接入：
 * 定位 CLI 可执行文件（PATH / 显式 / downloadUrl 拉取解压），随后以进程方式
 * 运行并回收结果。nemo-speech（Parakeet 等 NeMo Speech 系模型）与
 * whisper-cli / sherpa-onnx-offline 类外部运行时均走本运行器。</p>
 *
 * <h3>可执行文件定位顺序</h3>
 * <ol>
 *   <li>计算"安装目标"：显式 {@code deeplearning.cli.<id>.bin}（整路径）优先，其次
 *       {@code deeplearning.cli.<id>.dir}（目录 + 二进制名），再次默认缓存
 *       {@code <cacheRoot>/clis/<id>/<binName>}；该目标文件若已存在则直接使用</li>
 *   <li>PATH 查找（Windows 下按 {@code PATHEXT} 追加扩展名）</li>
 *   <li>downloadUrl（GitHub Releases 等）拉取 → SHA-256 校验 → 解压 →
 *       <b>安装到第 1 步算出的目标路径</b>（即自定义 {@code .bin}/{@code .dir} 不存在时，
 *       也会下载落到该配置路径，而非默认缓存）</li>
 * </ol>
 *
 * <h3>进程调用约定</h3>
 * <ul>
 *   <li>{@link #run(Path, String[], long)} 同步等待，stdout 全量回收</li>
 *   <li>超时（秒）由调用方指定；超时强杀并抛 {@link TimeoutException}</li>
 *   <li>退出码非 0 时抛 {@link IllegalStateException} 并携带 stdout/stderr 片段</li>
 * </ul>
 *
 * <h3>平台注意</h3>
 * <p>zip 走 JDK 内置 {@link ZipFile}，tar.gz 调系统 {@code tar}（Windows 10+ 自带，POSIX 自带）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class CliModelRunner {

    /** 系统属性前缀：{@code deeplearning.cli.<id>.bin}（整路径）/ {@code .dir}（目录），未命中时下载落到此目标 */
    private static final String PROP_CLI_BIN_PREFIX = "deeplearning.cli.";

    /** 缓存根目录名（相对 deeplearning.model.cache-dir，默认 %TEMP%） */
    private static final String CACHE_DIR_NAME = "chua-dl-models";

    /** CLI 安装子目录 */
    private static final String CLIS_SUBDIR = "clis";

    /** 进程调用默认超时（秒） */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    /**
     * 下载单次阻塞读超时（毫秒），非总时长上限。
     * <p>GitHub release 资产存在中途完全停摆的连接，取 60s 让哑火快速失败并由上层换镜像重试，
     * 慢而活跃的传输（读间隔远小于此值）不受影响。</p>
     */
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    /** 单文件下载大小上限（1GB） */
    private static final long MAX_DOWNLOAD_BYTES = 1L << 30;

    /** 错误消息截断窗口（字节） */
    private static final int ERROR_SNIPPET_BYTES = 2048;

    /** 实例化缓存：cliId -> 定位结果 */
    private static final Map<String, Path> LOCATED = new ConcurrentHashMap<>();

    /** 下载去重锁：url -> 锁对象 */
    private static final Map<String, Object> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();

    /**
     * 工具类私有构造
     */
    private CliModelRunner() {
    }

    /**
     * CLI 描述
     *
     * @param cliId          标识（如 {@code nemo-speech}）
     * @param binaryName     可执行文件名（Windows 下自动补 {@code .exe}）
     * @param downloadUrls   按平台顺序的下载 URL 列表（前者优先）
     * @param sha256FileName 可选：与下载同名的 {@code .sha256} 校验文件名（同源同目录）
     * @param entryPathIn    压缩包内可执行文件相对路径（{@code null}=根目录同名）
     */
    public record CliDescriptor(String cliId, String binaryName, List<String> downloadUrls,
                                String sha256FileName, String entryPathIn) {
    }

    /**
     * 工厂：nemo-speech 0.1.0（按 OS/ARCH/BACKEND 选 artifact）
     *
     * <p>Windows 默认 cpu 版（4.7MB zip，无 CUDA 依赖）；设 {@code nemo-speech.backend=cuda}
     * 拉 100MB cuda 版。mac aarch64 默认 metal。</p>
     *
     * @return 描述
     */
    public static CliDescriptor nemoSpeech() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean windows = os.contains("win");
        boolean mac = os.contains("mac");
        boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64");
        String osTag = windows ? "windows" : (mac ? "macos" : "linux");
        String archTag = (aarch64 && !windows) ? "aarch64" : "x86_64";
        String backend = System.getProperty("nemo-speech.backend", "cpu");
        if (mac && aarch64 && "metal".equalsIgnoreCase(backend)) {
            backend = "metal";
        }
        backend = backend.toLowerCase();
        String base = "https://github.com/NVIDIA/NeMo-Speech.cpp/releases/download/v0.1.0/";
        String name = "nemo-speech-0.1.0-" + osTag + "-" + archTag + "-" + backend;
        String ext = windows ? "zip" : "tar.gz";
        List<String> urls = new ArrayList<>();
        urls.add(base + name + "." + ext);
        if ("cuda".equals(backend) || "vulkan".equals(backend)) {
            urls.add(base + "nemo-speech-0.1.0-" + osTag + "-" + archTag + "-cpu." + ext);
        }
        return new CliDescriptor("nemo-speech", "nemo-speech", urls,
                name + "." + ext + ".sha256", null);
    }

    /** 默认 opencode 版本（GitHub Release tag） */
    private static final String DEFAULT_OPENCODE_VERSION = "v1.18.31";

    /**
    * 工厂：opencode CLI（自包含独立二进制，按 OS/ARCH 选 artifact）
    *
    * <p>产物形如 {@code opencode-windows-x64.zip} / {@code opencode-linux-x64.tar.gz} /
    * {@code opencode-darwin-arm64.zip}（bun 编译的单文件可执行程序，内嵌于压缩包根目录）。
    * 这些 CLI 独立二进制<b>没有</b>配套的逐文件 {@code .sha256}，故不做下载校验。
    * 版本默认 {@value #DEFAULT_OPENCODE_VERSION}，可设系统属性 {@code opencode.version} 覆盖。</p>
    *
    * @return 描述
    */
    public static CliDescriptor opencode() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean windows = os.contains("win");
        boolean mac = os.contains("mac");
        boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64");
        String osTag = windows ? "windows" : (mac ? "darwin" : "linux");
        String archTag = aarch64 ? "arm64" : "x64";
        String ext = (windows || mac) ? "zip" : "tar.gz";
        String version = System.getProperty("opencode.version", DEFAULT_OPENCODE_VERSION).trim();
        String base = "https://github.com/anomalyco/opencode/releases/download/" + version + "/";
        String name = "opencode-" + osTag + "-" + archTag + "." + ext;
        List<String> urls = new ArrayList<>();
        urls.add(base + name);
        // 无配套 .sha256；二进制位于压缩包根目录 → entryPathIn=null（仅取同名根二进制）
        return new CliDescriptor("opencode", "opencode", urls, null, null);
    }

    /**
     * 定位可执行文件（按描述的策略），结果缓存
     *
     * @param d 描述
     * @return 可执行文件路径
     * @throws IllegalStateException 定位失败
     */
    public static Path locate(CliDescriptor d) {
        Path cached = LOCATED.get(d.cliId());
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (CliModelRunner.class) {
            cached = LOCATED.get(d.cliId());
            if (cached != null && Files.isRegularFile(cached)) {
                return cached;
            }
            Path found = doLocate(d);
            if (found == null) {
                throw new IllegalStateException("CLI 定位失败: " + d.cliId()
                        + "（显式/已安装/PATH/下载 均未命中；可设 "
                        + PROP_CLI_BIN_PREFIX + d.cliId() + ".bin 显式路径）");
            }
            LOCATED.put(d.cliId(), found);
            return found;
        }
    }

    /**
     * 强制重定位（忽略缓存）
     *
     * @param d 描述
     * @return 路径；失败返回 空
     */
    public static Path rellocate(CliDescriptor d) {
        LOCATED.remove(d.cliId());
        try {
            return locate(d);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 定位实现
     *
     * @param d 描述
     * @return 路径；全失败返回 空
     */
    private static Path doLocate(CliDescriptor d) {
        // 1) 安装目标：显式 .bin / 自定义 .dir / 默认缓存；已存在则直接用
        Path target = installTarget(d);
        if (Files.isRegularFile(target)) {
            log.info("[cli-runner] {} 命中安装目标: {}", d.cliId(), target);
            return target;
        }
        // 2) PATH
        Path inPath = findInPath(d);
        if (inPath != null) {
            log.info("[cli-runner] {} 命中 PATH: {}", d.cliId(), inPath);
            return inPath;
        }
        // 3) downloadUrl → 安装到目标（自定义路径不存在时也落到该配置路径）
        log.info("[cli-runner] {} 目标不存在，准备下载安装到: {}", d.cliId(), target);
        return downloadAndInstall(d, target);
    }

    /**
     * 计算安装目标路径：显式 {@code .bin} 整路径 &gt; 自定义 {@code .dir} 目录 &gt; 默认缓存。
     *
     * @param d 描述
     * @return 目标可执行文件路径（不保证存在）
     */
    private static Path installTarget(CliDescriptor d) {
        String bin = System.getProperty(PROP_CLI_BIN_PREFIX + d.cliId() + ".bin");
        if (bin != null && !bin.isBlank()) {
            return Paths.get(bin.trim());
        }
        Path root = archiveRoot(d);
        Path found = findBinary(root, d);
        if (found != null) {
            return found;
        }
        return root.resolve("bin").resolve(exeName(d));
    }

    /**
     * 下载与解压根目录：自定义 {@code .dir} 目录 &gt; 默认缓存 {@code <cacheRoot>/clis/<cliId>}。
     * <p>压缩包按原始布局整树解到此目录，故 SDK 布局会形成 {@code root/bin/}、{@code root/share/}。</p>
     *
     * @param d 描述
     * @return 安装根
     */
    private static Path archiveRoot(CliDescriptor d) {
        String dir = System.getProperty(PROP_CLI_BIN_PREFIX + d.cliId() + ".dir");
        if (dir != null && !dir.isBlank()) {
            return Paths.get(dir.trim());
        }
        return cacheRoot().resolve(CLIS_SUBDIR).resolve(d.cliId());
    }

    /**
     * 平台相关可执行文件名（Windows 补 {@code .exe}）
     *
     * @param d 描述
     * @return 文件名
     */
    private static String exeName(CliDescriptor d) {
        return d.binaryName() + (isWindows() ? ".exe" : "");
    }

    /**
     * 在安装目录下探测可执行文件：entryPathIn 优先，其次 {@code bin/}，其次根目录，
     * 最后浅层遍历（DLL 与二进制必须同级，故整树保留）。
     *
     * @param root 安装根
     * @param d    描述
     * @return 路径；未命中返回 空
     */
    private static Path findBinary(Path root, CliDescriptor d) {
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        String exe = exeName(d);
        List<Path> candidates = new ArrayList<>();
        if (d.entryPathIn() != null && !d.entryPathIn().isBlank()) {
            candidates.add(root.resolve(d.entryPathIn().replace('\\', '/')));
        }
        candidates.add(root.resolve("bin").resolve(exe));
        candidates.add(root.resolve(exe));
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        try (var paths = Files.walk(root, 4)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(exe))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("[cli-runner] {} 遍历安装目录失败: {}", d.cliId(), e.getMessage());
            return null;
        }
    }

    /**
     * 缓存根：优先读系统属性，否则 %TEMP%/chua-dl-models
     *
     * @return 根
     */
    public static Path cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop.trim());
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), CACHE_DIR_NAME);
    }

    /**
     * 是否 Windows
     *
     * @return 是否
     */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 在 PATH 中查找可执行文件（Windows 含 PATHEXT）
     *
     * @param d 描述
     * @return 路径；未命中返回 空
     */
    private static Path findInPath(CliDescriptor d) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(d.binaryName());
        if (isWindows()) {
            String pathext = System.getenv("PATHEXT");
            if (pathext != null) {
                for (String ext : pathext.split(";")) {
                    String e = ext.trim();
                    if (!e.isEmpty()) {
                        candidates.add(d.binaryName() + e);
                    }
                }
            } else {
                candidates.add(d.binaryName() + ".exe");
            }
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String c : candidates) {
                Path p = Paths.get(dir).resolve(c);
                if (Files.isRegularFile(p)) {
                    return p.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    /**
     * 下载并整树安装到安装根目录
     *
     * @param d       描述
     * @param install 约定二进制落点（可能非实际布局位置）
     * @return 可执行文件路径；全失败返回 空
     */
    private static Path downloadAndInstall(CliDescriptor d, Path install) {
        Path root = extractRoot(d, install);
        for (String url : d.downloadUrls()) {
            try {
                log.info("[cli-runner] {} 开始下载安装: {} -> {}", d.cliId(), url, root);
                Files.createDirectories(root);
                Object lock = DOWNLOAD_LOCKS.computeIfAbsent(url, k -> new Object());
                synchronized (lock) {
                    Path existing = findBinary(root, d);
                    if (existing != null) {
                        return existing;
                    }
                    Path tmp = downloadSingle(url, root);
                    if (tmp == null) {
                        continue;
                    }
                    verifySha256(url, d, tmp);
                    installArchive(d, tmp, root);
                    Files.deleteIfExists(tmp);
                    Path installed = findBinary(root, d);
                    if (installed == null) {
                        throw new IOException("解压后未找到主二进制: " + root
                                + "（entryPathIn=" + d.entryPathIn() + "）");
                    }
                    log.info("[cli-runner] {} 安装完成: {}", d.cliId(), installed);
                    return installed;
                }
            } catch (Exception e) {
                log.warn("[cli-runner] {} 下载安装失败: {} -> {}: {}", d.cliId(), url,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 解压根：安装根；显式 {@code .bin} 无安装根概念时退回其父目录
     *
     * @param d       描述
     * @param install 约定二进制落点
     * @return 解压根
     */
    private static Path extractRoot(CliDescriptor d, Path install) {
        String bin = System.getProperty(PROP_CLI_BIN_PREFIX + d.cliId() + ".bin");
        if (bin != null && !bin.isBlank()) {
            Path parent = install.getParent();
            return parent != null ? parent : archiveRoot(d);
        }
        return archiveRoot(d);
    }

    /**
     * 校验 SHA-256（可选）；不匹配抛异常
     *
     * @param url  源
     * @param d    描述
     * @param file 下载文件
     * @throws IOException 不匹配 / IO 失败
     */
    private static void verifySha256(String url, CliDescriptor d, Path file) throws IOException {
        if (d.sha256FileName() == null || d.sha256FileName().isBlank()) {
            return;
        }
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        URL shaUrl = new URL(base + d.sha256FileName());
        String raw;
        try (InputStream in = shaUrl.openStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // GitHub Release 的 .sha256 是 sha256sum 格式："<hex>  <filename>"（可能 CRLF）
        String[] tokens = raw.trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].length() < 64) {
            throw new IOException("SHA-256 摘要文件无法解析: " + d.sha256FileName()
                    + " 内容首行=" + firstLine(raw));
        }
        String expected = tokens[0];
        String actual = sha256Hex(file);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 校验失败: " + actual + " != " + expected + " (" + file + ")");
        }
        log.info("[cli-runner] SHA-256 校验通过: {}", d.sha256FileName());
    }

    /**
     * 取文本首行（用于错误消息展示）
     *
     * @param s 文本
     * @return 首行
     */
    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }

    /**
     * 整树解压到安装根目录（保留压缩包内相对布局）
     *
     * @param d       描述
     * @param archive 压缩文件
     * @param outDir  解压根（= 安装根）
     * @throws IOException 失败
     */
    private static void installArchive(CliDescriptor d, Path archive, Path outDir) throws IOException {
        String lower = archive.getFileName().toString().toLowerCase();
        if (lower.endsWith(".zip")) {
            try (ZipFile zf = new ZipFile(archive.toFile())) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String rawName = e.getName();
                    boolean dirEntry = e.isDirectory() || rawName.endsWith("/") || rawName.endsWith("\\");
                    if (dirEntry) {
                        Path dir = resolvedEntry(outDir, rawName);
                        if (dir != null) {
                            Files.createDirectories(dir);
                        }
                        continue;
                    }
                    Path target = resolvedEntry(outDir, rawName);
                    if (target == null) {
                        log.warn("[cli-runner] 跳过不安全 entry: {}", e.getName());
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            installFromTarGz(archive, outDir);
        } else {
            throw new IOException("未知压缩格式: " + lower);
        }
    }

    /**
     * 计算 zip entry 在解压目录下的目标路径，拒绝逃逸出解压目录的 entry。
     * <p>entry 名可能以反斜杠分隔（Windows 打包的 SDK 布局），统一归一化为 {@code /}。</p>
     *
     * @param outDir 解压目录
     * @param name   entry 名
     * @return 目标；不安全或越界返回 空
     */
    private static Path resolvedEntry(Path outDir, String name) {
        String norm = name.replace('\\', '/');
        if (norm.contains("..") || norm.startsWith("/") || norm.contains(":")) {
            return null;
        }
        Path root = outDir.toAbsolutePath().normalize();
        Path target = root.resolve(norm).normalize();
        return target.startsWith(root) ? target : null;
    }

    /**
     * 从 tar.gz 安装（POSIX / Windows 10+ 系统 tar）
     *
     * @param archive 压缩
     * @param outDir  目标目录
     * @throws IOException 失败
     */
    private static void installFromTarGz(Path archive, Path outDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", outDir.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tar 超时: " + archive);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("tar 退出码 " + p.exitValue() + ": " +
                    new String(out, 0, Math.min(out.length, ERROR_SNIPPET_BYTES), StandardCharsets.UTF_8));
        }
    }

    /**
     * 单文件下载（支持 3xx 重定向、临时文件原子写、大小上限）
     *
     * @param url 源
     * @param dir 目标目录
     * @return 文件；失败返回 空
     */
    private static Path downloadSingle(String url, Path dir) throws IOException {
        URL u = new URL(url);
        int i = u.getPath().lastIndexOf('/');
        String fileName = i >= 0 ? u.getPath().substring(i + 1) : "download-" + System.currentTimeMillis();
        Path tmp = dir.resolve(fileName + ".part");
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = conn.getHeaderField("Location");
            if (loc == null) {
                throw new IOException("重定向缺 Location: " + url);
            }
            conn.disconnect();
            return downloadSingle(loc, dir);
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + ": " + url);
        }
        long contentLength = conn.getContentLengthLong();
        if (contentLength > MAX_DOWNLOAD_BYTES) {
            conn.disconnect();
            throw new IOException("文件大小超过上限 " + MAX_DOWNLOAD_BYTES + " 字节: " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.createDirectories(dir);
            long copied = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (copied > MAX_DOWNLOAD_BYTES) {
                Files.deleteIfExists(tmp);
                throw new IOException("下载内容超过上限: " + url);
            }
            Path target = dir.resolve(fileName);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[cli-runner] 下载完成 {} ({} 字节) -> {}", fileName, copied, target);
            return target;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 同步运行可执行文件
     *
     * @param exePath    可执行文件
     * @param args       参数
     * @param timeoutSec 超时（秒）
     * @return stdout 文本
     * @throws IOException 启动/IO 失败
     * @throws InterruptedException 等待中断
     * @throws TimeoutException 超时
     * @throws IllegalStateException 退出码非 0
     */
    public static String run(Path exePath, String[] args, long timeoutSec)
            throws IOException, InterruptedException, TimeoutException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exePath.toString());
        if (args != null) {
            for (String a : args) {
                if (a != null) {
                    cmd.add(a);
                }
            }
        }
        log.debug("[cli-runner] 运行: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        // 关闭子进程 stdin：Java 起的空管道永不 EOF，避免 bun 类 CLI 阻塞等待输入
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        StringBuilder err = new StringBuilder();
        Thread errThread = Thread.startVirtualThread(() -> {
            try (var in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (err) {
                        if (err.length() < 16_384) {
                            err.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        });
        String out;
        try (var os = p.getInputStream()) {
            out = new String(os.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            errThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean ok = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new TimeoutException("CLI 超时 " + timeoutSec + "s: " + exePath + " "
                    + (args == null ? "" : String.join(" ", args)));
        }
        int code = p.exitValue();
        if (code != 0) {
            synchronized (err) {
                throw new IllegalStateException("CLI 退出码 " + code + " (" + exePath.getFileName() + "): "
                        + snippet(out) + " | stderr: " + snippet(String.valueOf(err)));
            }
        }
        return out;
    }

    /**
     * 默认超时运行
     *
     * @param exePath 可执行
     * @param args    参数
     * @return stdout
     * @throws Exception 失败
     */
    public static String run(Path exePath, String[] args) throws Exception {
        return run(exePath, args, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 流式运行可执行文件：进程存活期间按行读取 stdout，逐行回调 {@code onLine}。
     *
     * <p>用于 NDJSON 事件流（如 {@code opencode run --format json}），调用方每收到一行即可解析处理，
     * 无需等待进程结束。{@code onLine} 抛出的运行时异常会强杀进程并向外传播。</p>
     *
     * @param exePath    可执行文件
     * @param args       参数
     * @param timeoutSec 总超时（秒），超时由看门狗强杀进程
     * @param onLine     每行 stdout 回调（可为 null，仅等待进程结束）
     * @throws IOException 启动/IO 失败
     * @throws InterruptedException 等待中断
     * @throws TimeoutException 超时被强杀
     * @throws IllegalStateException 退出码非 0（携带 stderr 片段）
     */
    public static void runStream(Path exePath, String[] args, long timeoutSec, Consumer<String> onLine)
            throws IOException, InterruptedException, TimeoutException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exePath.toString());
        if (args != null) {
            for (String a : args) {
                if (a != null) {
                    cmd.add(a);
                }
            }
        }
        log.debug("[cli-runner] 流式运行: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        // 关闭子进程 stdin：Java 起的空管道永不 EOF，避免 bun 类 CLI 阻塞等待输入
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        StringBuilder err = new StringBuilder();
        Thread errThread = Thread.startVirtualThread(() -> {
            try (var in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (err) {
                        if (err.length() < 16_384) {
                            err.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        });
        // 看门狗：到点强杀，使阻塞在 readLine 的当前线程收到 EOF/异常而解除
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "cli-runner-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (onLine != null) {
                        onLine.accept(line);
                    }
                }
            }
        } catch (RuntimeException e) {
            // 回调抛错：立即强杀进程，稍后向上抛
            p.destroyForcibly();
            throw e;
        } finally {
            watchdog.interrupt();
            try {
                errThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        p.waitFor();
        if (timedOut.get()) {
            throw new TimeoutException("CLI 超时 " + timeoutSec + "s: " + exePath + " "
                    + (args == null ? "" : String.join(" ", args)));
        }
        int code = p.exitValue();
        if (code != 0) {
            synchronized (err) {
                throw new IllegalStateException("CLI 退出码 " + code + " (" + exePath.getFileName() + "): stderr "
                        + snippet(String.valueOf(err)));
            }
        }
    }

    /**
     * 截取首尾片段
     *
     * @param s 文本
     * @return 片段
     */
    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.strip();
        if (s.length() <= ERROR_SNIPPET_BYTES * 2) {
            return s;
        }
        return s.substring(0, ERROR_SNIPPET_BYTES) + " ... " + s.substring(s.length() - ERROR_SNIPPET_BYTES);
    }

    /**
     * SHA-256 hex
     *
     * @param file 文件
     * @return hex
     * @throws IOException 失败
     */
    private static String sha256Hex(Path file) throws IOException {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 清空定位缓存
     */
    public static void clearCache() {
        LOCATED.clear();
        DOWNLOAD_LOCKS.clear();
    }
}
