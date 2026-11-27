package com.chua.codedoc.support;

import com.chua.common.support.matcher.MatcherUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 深度优先扫描器：排除目录提前剪枝，不跟随符号链接和目录联接。
 *
 * @author CH
 * @since 4.0.0.51
 */
final class SourceScanner {
    private SourceScanner() {
    }

    static List<Path> scan(CodeDocOptions options, List<CodeDocResult.Issue> issues) throws IOException {
        List<Path> result = new ArrayList<>();
        Path root = options.directory();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = relative(root, dir);
                if (dir.startsWith(options.outputDirectory()) || Files.isSymbolicLink(dir)
                        || attrs.isOther() || matches(options.excludes(), relative, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (root.relativize(dir).getNameCount() > options.maxDepth()) {
                    issues.add(new CodeDocResult.Issue(relative, "SCAN", "超过 maxDepth，已跳过目录"));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = relative(root, file);
                String type = extension(file);
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
                        || matches(options.excludes(), relative, false)
                        || options.excludeTypes().contains(type)
                        || !options.includeTypes().isEmpty() && !options.includeTypes().contains(type)
                        || !options.includes().isEmpty() && !matches(options.includes(), relative, false)) {
                    return FileVisitResult.CONTINUE;
                }
                if (result.size() >= options.maxFiles()) {
                    throw new IOException("匹配文件超过 maxFiles=" + options.maxFiles() + "，请缩小范围或提高上限");
                }
                result.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (options.failFast()) {
                    throw new IOException("无法扫描源码路径: " + relative(root, file), exc);
                }
                issues.add(new CodeDocResult.Issue(relative(root, file), "SCAN", "路径不可访问"));
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(path -> relative(root, path)));
        return result;
    }

    static boolean matches(List<String> patterns, String relative, boolean directory) {
        for (String pattern : patterns) {
            String normalized = pattern.replace('\\', '/');
            if (normalized.endsWith("/")) {
                normalized += "**";
            }
            if (MatcherUtils.matchPath(normalized, relative)
                    || directory && MatcherUtils.matchPath(normalized, relative + "/")) {
                return true;
            }
            // 目录模式本身也参与剪枝；裸名称匹配任意层级的同名目录。
            if (directory && (normalized.endsWith("/**")
                    && MatcherUtils.matchPath(normalized.substring(0, normalized.length() - 3), relative)
                    || normalized.indexOf('/') < 0
                    && relative.substring(relative.lastIndexOf('/') + 1).equals(normalized))) {
                return true;
            }
        }
        return false;
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String read(Path file, CodeDocOptions options) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("扫描后文件已改变或不是常规文件");
        }
        // 限量读，避免扫描后文件突然增长而导致 readAllBytes 无界分配。
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes((int) options.maxFileBytes() + 1);
        }
        if (bytes.length > options.maxFileBytes()) {
            throw new IOException("文件超过 maxFileBytes=" + options.maxFileBytes());
        }
        String text = options.charset().newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\u0000') >= 0) {
            throw new IOException("文件包含二进制 NUL，不能按源码处理");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
