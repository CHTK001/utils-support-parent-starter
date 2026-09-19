package com.chua.common.support.lang.cmd;

import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 可执行文件定位器，统一"某个 CLI 程序到底装在哪"的查找逻辑。
 *
 * <p>此前各模块各自实现了四套互不相同的定位方式：有的扫 {@code PATH} 环境变量、
 * 有的起 {@code which}/{@code where} 子进程、有的硬编码常见安装目录、
 * 有的干脆只写一个程序名交给系统去找。本类把这些方式收敛为固定的优先级顺序。</p>
 *
 * <h3>查找优先级</h3>
 * <ol>
 *   <li>显式路径（{@link LocateRequest#explicitPath()}）— 配置或用户指定，最可信</li>
 *   <li>环境变量指向的路径（如 {@code TSHARK_BIN}）— 允许运维侧覆盖</li>
 *   <li>{@code PATH} 环境变量扫描 — 纯 Java 实现，不启动子进程，覆盖绝大多数场景</li>
 *   <li>候选安装目录 — 应对未加入 PATH 的安装（如 Windows 的 {@code C:\Program Files}）</li>
 *   <li>{@code which}/{@code where} 子进程 — 兜底，Windows 下能查到注册表 App Paths 里的程序</li>
 * </ol>
 *
 * <p>定位结果按可执行名缓存，避免每次调用都重复扫描磁盘。
 * 若运行期安装了新软件，可调用 {@link #clearCache()} 失效缓存。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Optional<Path> ffmpeg = ExecutableLocator.locate("ffmpeg");
 *
 * Optional<Path> tshark = ExecutableLocator.locate(ExecutableLocator.LocateRequest.builder("tshark")
 *         .envKey("TSHARK_BIN")
 *         .candidateDirs("/usr/bin", "/usr/local/bin", "C:\\Program Files\\Wireshark")
 *         .build());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ExecutableLocator {

    /**
     * 环境变量名：PATH
    */
    private static final String PATH_ENV = "PATH";

    /**
     * PATH 环境变量的分隔符
    */
    private static final String PATH_SEPARATOR = java.io.File.pathSeparator;

    /**
     * 系统查找命令的超时时间（秒）
    */
    private static final long LOOKUP_TIMEOUT_SECONDS = 5L;

    /**
     * 定位结果缓存：请求指纹 → 定位结果
    */
    private static final ConcurrentHashMap<String, Optional<Path>> CACHE = new ConcurrentHashMap<>();

    /**
     * 创建 ExecutableLocator 实例
    */
    private ExecutableLocator() {
    }

    /**
     * 按可执行文件名定位，使用默认查找策略。
     *
     * @param executableName 可执行文件名，如 {@code ffmpeg}
     * @return 定位到的绝对路径，找不到时返回 {@link Optional#empty()}
     */
    @Nonnull
    public static Optional<Path> locate(@Nonnull String executableName) {
        return locate(LocateRequest.of(executableName));
    }

    /**
     * 按完整查找请求定位可执行文件。
     *
     * @param request 查找请求
     * @return 定位到的绝对路径，找不到时返回 {@link Optional#empty()}
     */
    @Nonnull
    public static Optional<Path> locate(@Nonnull LocateRequest request) {
        String cacheKey = request.cacheKey();
        Optional<Path> cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Optional<Path> result = doLocate(request);
        CACHE.put(cacheKey, result);
        return result;
    }

    /**
     * 清空定位结果缓存。
     *
     * <p>运行期新安装了 CLI 软件、或 PATH 发生变化后调用此方法，
     * 使下一次定位重新扫描磁盘。</p>
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 执行实际的定位流程，按优先级依次尝试各查找策略。
     *
     * @param request 查找请求
     * @return 定位结果
     */
    private static Optional<Path> doLocate(LocateRequest request) {
        if (request.explicitPath() != null) {
            Optional<Path> found = toExecutableFile(request.explicitPath());
            if (found.isPresent()) {
                return found;
            }
        }

        if (request.envKey() != null) {
            Optional<Path> found = locateFromEnv(request.envKey(), request.executableNames());
            if (found.isPresent()) {
                return found;
            }
        }

        for (String name : request.executableNames()) {
            if (request.searchPath()) {
                Optional<Path> found = locateInPath(name);
                if (found.isPresent()) {
                    return found;
                }
            }

            Optional<Path> found = locateInDirectories(name, request.candidateDirs());
            if (found.isPresent()) {
                return found;
            }

            if (request.systemLookup()) {
                found = locateBySystemCommand(name);
                if (found.isPresent()) {
                    return found;
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 从环境变量指定的路径定位。
     *
     * <p>环境变量的值既可能是可执行文件本身（如 {@code FFMPEG_BIN=/opt/bin/ffmpeg}），
     * 也可能是其所在目录（如 {@code JAVA_HOME=/opt/jdk}），两种情况都兼容。</p>
     *
     * @param envKey 环境变量名
     * @param names  可执行文件名候选
     * @return 定位结果
     */
    private static Optional<Path> locateFromEnv(String envKey, List<String> names) {
        String value = System.getenv(envKey);
        if (StringUtils.isNullOrEmpty(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();

        Optional<Path> file = toExecutableFile(trimmed);
        if (file.isPresent()) {
            return file;
        }

        for (String name : names) {
            Optional<Path> found = matchInDirectory(trimmed, name);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 在 PATH 环境变量列出的目录中查找。
     *
     * @param executableName 可执行文件名
     * @return 定位结果
     */
    private static Optional<Path> locateInPath(String executableName) {
        String pathEnv = System.getenv(PATH_ENV);
        if (StringUtils.isNullOrEmpty(pathEnv)) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(PATH_SEPARATOR)) {
            if (StringUtils.isNullOrEmpty(dir)) {
                continue;
            }
            Optional<Path> found = matchInDirectory(dir.trim(), executableName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 在候选安装目录中查找。
     *
     * @param executableName 可执行文件名
     * @param candidateDirs  候选目录
     * @return 定位结果
     */
    private static Optional<Path> locateInDirectories(String executableName, List<String> candidateDirs) {
        for (String dir : candidateDirs) {
            if (StringUtils.isNullOrEmpty(dir)) {
                continue;
            }
            Optional<Path> found = matchInDirectory(dir.trim(), executableName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 通过系统的 {@code which}（Unix）或 {@code where}（Windows）命令兜底查找。
     *
     * <p>Windows 的 {@code where} 能查到注册表中 App Paths 登记的程序，
     * 这类程序不在 PATH 中，纯 Java 扫描无法发现。</p>
     *
     * @param executableName 可执行文件名
     * @return 定位结果
     */
    private static Optional<Path> locateBySystemCommand(String executableName) {
        boolean windows = OsFamily.current().isWindows();
        String lookupCommand = windows ? "where" : "which";
        try {
            CmdResult result = CmdExecutors.execute(
                    new String[]{lookupCommand, executableName},
                    LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!result.isSuccess()) {
                return Optional.empty();
            }
            String stdout = result.getStdout();
            if (StringUtils.isNullOrEmpty(stdout)) {
                return Optional.empty();
            }
            // where 命令可能输出多行，取第一行作为结果
            for (String line : stdout.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Optional<Path> found = toExecutableFile(trimmed);
                if (found.isPresent()) {
                    return found;
                }
            }
        } catch (Exception ignored) {
            // 系统查找命令不可用或执行失败时，静默降级为未找到
        }
        return Optional.empty();
    }

    /**
     * 在单个目录中按可执行名匹配文件。
     *
     * <p>若可执行名已带扩展名则直接匹配；否则按当前平台的候选扩展名依次尝试。
     * Windows 下还会尝试 {@code .exe}、{@code .bat}、{@code .cmd}。</p>
     *
     * @param dir            目录路径
     * @param executableName 可执行文件名
     * @return 定位结果
     */
    private static Optional<Path> matchInDirectory(String dir, String executableName) {
        Path directory = toPath(dir);
        if (directory == null) {
            return Optional.empty();
        }
        Optional<Path> direct = toExecutableFile(directory.resolve(executableName).toString());
        if (direct.isPresent()) {
            return direct;
        }
        for (String suffix : OsFamily.current().executableSuffixes()) {
            if (suffix.isEmpty()) {
                continue;
            }
            Optional<Path> found = toExecutableFile(directory.resolve(executableName + suffix).toString());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 校验路径是否指向一个可用的可执行文件。
     *
     * @param path 待校验路径
     * @return 可用时返回规范化后的绝对路径
     */
    private static Optional<Path> toExecutableFile(String path) {
        Path candidate = toPath(path);
        if (candidate == null) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        if (!Files.isExecutable(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate.toAbsolutePath().normalize());
    }

    /**
     * 字符串转路径，非法路径返回 null。
     *
     * @param path 路径字符串
     * @return 路径对象，非法时返回 null
     */
    @Nullable
    private static Path toPath(String path) {
        if (StringUtils.isNullOrEmpty(path)) {
            return null;
        }
        try {
            return Paths.get(path.trim());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 可执行文件查找请求。
     *
     * <p>通过 {@link #builder(String)} 创建，除可执行名外均为可选项。</p>
     */
    public static final class LocateRequest {

        /**
         * 候选可执行文件名，按优先级排序
        */
        private final List<String> executableNames;
        /**
         * 显式指定的完整路径
        */
        private final String explicitPath;
        /**
         * 环境变量键
        */
        private final String envKey;
        /**
         * 候选安装目录
        */
        private final List<String> candidateDirs;
        /**
         * 是否扫描 PATH
        */
        private final boolean searchPath;
        /**
         * 是否使用 which/where 兜底
        */
        private final boolean systemLookup;

        /**
         * 创建查找请求
         *
         * @param builder 构建器
         */
        private LocateRequest(Builder builder) {
            this.executableNames = Collections.unmodifiableList(new ArrayList<>(builder.executableNames));
            this.explicitPath = builder.explicitPath;
            this.envKey = builder.envKey;
            this.candidateDirs = Collections.unmodifiableList(new ArrayList<>(builder.candidateDirs));
            this.searchPath = builder.searchPath;
            this.systemLookup = builder.systemLookup;
        }

        /**
         * 创建仅包含可执行文件名的查找请求。
         *
         * @param executableName 可执行文件名
         * @return 查找请求
         */
        @Nonnull
        public static LocateRequest of(@Nonnull String executableName) {
            return builder(executableName).build();
        }

        /**
         * 创建构建器。
         *
         * @param executableName 主可执行文件名
         * @return 构建器
         */
        @Nonnull
        public static Builder builder(@Nonnull String executableName) {
            return new Builder(executableName);
        }

        /**
         * 获取候选可执行文件名列表。
         *
         * @return 可执行文件名列表
         */
        @Nonnull
        public List<String> executableNames() {
            return executableNames;
        }

        /**
         * 获取显式指定的路径。
         *
         * @return 显式路径，未指定返回 null
         */
        @Nullable
        public String explicitPath() {
            return explicitPath;
        }

        /**
         * 获取环境变量键。
         *
         * @return 环境变量键，未指定返回 null
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
         * 是否扫描 PATH 环境变量。
         *
         * @return 扫描返回 true
         */
        public boolean searchPath() {
            return searchPath;
        }

        /**
         * 是否使用系统命令兜底查找。
         *
         * @return 启用返回 true
         */
        public boolean systemLookup() {
            return systemLookup;
        }

        /**
         * 生成缓存键，包含所有影响定位结果的字段。
         *
         * @return 缓存键
         */
        @Nonnull
        String cacheKey() {
            return executableNames + "|" + explicitPath + "|" + envKey + "|"
                    + candidateDirs + "|" + searchPath + "|" + systemLookup;
        }

        /**
         * {@link LocateRequest} 构建器。
         */
        public static final class Builder {

            /**
             * 候选可执行文件名，去重且保持顺序
            */
            private final Set<String> executableNames = new LinkedHashSet<>();
            /**
             * 候选目录，去重且保持顺序
            */
            private final Set<String> candidateDirs = new LinkedHashSet<>();
            /**
             * 显式路径
            */
            private String explicitPath;
            /**
             * 环境变量键
            */
            private String envKey;
            /**
             * 是否扫描 PATH
            */
            private boolean searchPath = true;
            /**
             * 是否使用系统命令兜底
            */
            private boolean systemLookup = true;

            /**
             * 创建构建器
             *
             * @param executableName 主可执行文件名
             */
            private Builder(String executableName) {
                this.executableNames.add(executableName);
            }

            /**
             * 追加备选可执行文件名，在当前平台主名称不可用时依次尝试。
             *
             * @param name 备选可执行名
             * @return this
             */
            @Nonnull
            public Builder alias(@Nonnull String name) {
                if (!StringUtils.isNullOrEmpty(name)) {
                    this.executableNames.add(name);
                }
                return this;
            }

            /**
             * 设置显式路径，优先级高于其他所有查找方式。
             *
             * @param path 可执行文件的完整路径
             * @return this
             */
            @Nonnull
            public Builder explicitPath(@Nullable String path) {
                this.explicitPath = path;
                return this;
            }

            /**
             * 设置用于覆盖定位结果的环境变量名。
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
             * 追加候选安装目录，可多次调用。
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
             * 追加候选安装目录列表。
             *
             * @param dirs 候选目录列表
             * @return this
             */
            @Nonnull
            public Builder candidateDirs(@Nonnull List<String> dirs) {
                for (String dir : dirs) {
                    if (!StringUtils.isNullOrEmpty(dir)) {
                        this.candidateDirs.add(dir);
                    }
                }
                return this;
            }

            /**
             * 设置是否扫描 PATH 环境变量，默认开启。
             *
             * @param searchPath 开启返回 true
             * @return this
             */
            @Nonnull
            public Builder searchPath(boolean searchPath) {
                this.searchPath = searchPath;
                return this;
            }

            /**
             * 设置是否使用 which/where 命令兜底，默认开启。
             *
             * @param systemLookup 开启返回 true
             * @return this
             */
            @Nonnull
            public Builder systemLookup(boolean systemLookup) {
                this.systemLookup = systemLookup;
                return this;
            }

            /**
             * 构建查找请求。
             *
             * @return 查找请求
             */
            @Nonnull
            public LocateRequest build() {
                return new LocateRequest(this);
            }
        }
    }
}
