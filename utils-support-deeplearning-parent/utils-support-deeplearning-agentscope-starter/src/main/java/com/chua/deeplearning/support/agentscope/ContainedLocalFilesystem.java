package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.utils.PathContainment;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.LocalFsMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 为 SDK 本地文件工具补充真实路径边界；保留 SDK 的直接文件操作与命名空间语义。
 *
 * @author CH
 * @since 4.0.0.51
 */
class ContainedLocalFilesystem extends LocalFilesystem {

    private static final long SEARCH_MAX_BYTES = 10L * 1024 * 1024;
    private final List<Path> roots;

    /**
     * @param source SDK 创建的文件系统配置
     * @param namespaceFactory 原命名空间工厂
     */
    ContainedLocalFilesystem(LocalFilesystem source, NamespaceFactory namespaceFactory) {
        super(source.getCwd(), source.getMode(), source.getPathPolicy(), 10, namespaceFactory);
        List<Path> allowed = new ArrayList<>();
        allowed.add(getCwd());
        if (getMode() == LocalFsMode.ROOTED) {
            allowed.addAll(getPathPolicy().roots());
        }
        this.roots = List.copyOf(allowed);
    }

    @Override
    protected Path resolvePath(RuntimeContext context, String path) {
        Path resolved = super.resolvePath(context, path);
        return getMode() == LocalFsMode.UNRESTRICTED ? resolved : PathContainment.requireWithin(resolved, roots);
    }

    /**
     * 在读取目录项属性或文件内容前检查真实目标；遍历不跟随目录链接。
     *
     * @param path 待检查的遍历项
     * @return 是否允许访问
     */
    private boolean allowed(Path path) {
        if (getMode() == LocalFsMode.UNRESTRICTED) {
            return true;
        }
        try {
            PathContainment.requireWithin(path, roots);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * @param context 运行上下文
     * @param path 物理路径
     * @return 与 SDK 一致的可见路径
     */
    private String visible(RuntimeContext context, Path path) {
        String relative = getCwd().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (getMode() == LocalFsMode.SANDBOXED) {
            return "/" + relative;
        }
        if (getNamespaceFactory() != null) {
            List<String> namespace = getNamespaceFactory().getNamespace(context);
            String prefix = namespace == null || namespace.isEmpty() ? "" : String.join("/", namespace) + "/";
            if (!prefix.isEmpty() && relative.startsWith(prefix)) {
                return relative.substring(prefix.length());
            }
        }
        return relative;
    }

    @Override
    public LsResult ls(RuntimeContext context, String path) {
        Path directory = resolvePath(context, path);
        if (!Files.isDirectory(directory)) {
            return LsResult.fail("Not a directory: " + path);
        }
        List<FileInfo> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (!allowed(entry)) {
                    continue;
                }
                try {
                    var attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    String name = visible(context, entry);
                    String modified = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString();
                    entries.add(attrs.isDirectory() ? FileInfo.ofDir(name + "/", modified)
                            : FileInfo.ofFile(name, attrs.size(), modified));
                } catch (IOException ignored) {
                    // 与 SDK 一致，跳过不可读条目。
                }
            }
        } catch (IOException e) {
            return LsResult.fail("Cannot list directory: " + path);
        }
        entries.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(entries);
    }

    @Override
    public GlobResult glob(RuntimeContext context, String pattern, String path) {
        Path base = resolvePath(context, path == null || "/".equals(path) ? "." : path);
        if (!Files.isDirectory(base)) {
            return GlobResult.success(List.of());
        }
        String effective = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        String recursive = effective.startsWith("**") ? effective : "**/" + effective;
        String direct = effective.startsWith("**/") ? effective.substring(3)
                : "**".equals(effective) ? "*" : effective;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + recursive);
        PathMatcher directMatcher = FileSystems.getDefault().getPathMatcher("glob:" + direct);
        List<FileInfo> matches = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    return allowed(directory) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relative = base.relativize(file);
                    if (allowed(file) && (matcher.matches(relative) || directMatcher.matches(relative))) {
                        String modified = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString();
                        matches.add(FileInfo.ofFile(visible(context, file), attrs.size(), modified));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return GlobResult.fail("Cannot glob path: " + path);
        }
        matches.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(matches);
    }

    @Override
    public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) {
        Path base = resolvePath(context, path == null ? "." : path);
        PathMatcher matcher = glob == null || glob.isBlank() ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<GrepMatch> matches = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    return allowed(directory) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!allowed(file) || !Files.isRegularFile(file)
                            || (matcher != null && !matcher.matches(file.getFileName())
                            && !matcher.matches(base.relativize(file)))) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (Files.size(file) > SEARCH_MAX_BYTES) {
                            return FileVisitResult.CONTINUE;
                        }
                        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                            String line;
                            int number = 0;
                            while ((line = reader.readLine()) != null) {
                                number++;
                                if (line.contains(pattern)) {
                                    matches.add(new GrepMatch(visible(context, file), number, line));
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // 二进制、不可读及并发删除的文件不进入结果。
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return GrepResult.fail("Cannot search path: " + path);
        }
        return GrepResult.success(matches);
    }
}
