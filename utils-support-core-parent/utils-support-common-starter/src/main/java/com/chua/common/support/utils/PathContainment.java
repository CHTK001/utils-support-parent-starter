package com.chua.common.support.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

/**
 * 校验词法路径和真实路径均位于同一个授权根下。
 *
 * <p>不存在的尾部按最近的现存祖先解析，支持安全地创建新文件；悬空链接、循环链接、
 * 无法读取的祖先一律拒绝。该校验不提供操作系统沙箱，也不消除校验后被其它进程替换目录的竞态。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class PathContainment {

    private PathContainment() {
    }

    /**
     * 校验路径，保留原词法路径以维持调用方的链接与移动语义。
     *
     * @param candidate 待访问路径
     * @param roots 授权根集合；根本身视为受信配置，可通过链接定位
     * @return 绝对且归一化的原路径
     * @throws SecurityException 越界或无法可靠解析
     */
    public static Path requireWithin(Path candidate, Collection<Path> roots) {
        Path absolute = candidate.toAbsolutePath().normalize();
        for (Path root : roots) {
            Path allowed = root.toAbsolutePath().normalize();
            if (!absolute.startsWith(allowed)) {
                continue;
            }
            try {
                if (realOrMissing(absolute).startsWith(realOrMissing(allowed))) {
                    return absolute;
                }
            } catch (IOException e) {
                throw new SecurityException("Cannot verify filesystem path: " + absolute, e);
            }
        }
        throw new SecurityException("Filesystem path outside allowed roots: " + absolute);
    }

    /**
     * 单根校验。
     *
     * @param root 授权根
     * @param candidate 待访问路径
     * @return 校验后的绝对路径
     */
    public static Path requireWithin(Path root, Path candidate) {
        return requireWithin(candidate, java.util.List.of(root));
    }

    /**
     * 校验账号等固定归属目录未通过链接重定向到其它位置。
     *
     * @param root 受信基路径
     * @param candidate 固定归属的后代路径
     * @return 校验后的绝对路径
     */
    public static Path requireUnaliased(Path root, Path candidate) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absolute = requireWithin(absoluteRoot, candidate);
        try {
            Path expected = realOrMissing(absoluteRoot).resolve(absoluteRoot.relativize(absolute));
            if (!realOrMissing(absolute).equals(expected)) {
                throw new SecurityException("Owned directory redirected: " + absolute);
            }
            return absolute;
        } catch (IOException e) {
            throw new SecurityException("Cannot verify owned directory: " + absolute, e);
        }
    }

    /**
     * 解析真实祖先并接回尚不存在的尾部，不把悬空链接误当作普通缺失目录。
     *
     * @param path 绝对路径
     * @return 真实路径或基于真实祖先的待创建路径
     * @throws IOException 祖先不可读或链接不可解析
     */
    private static Path realOrMissing(Path path) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        Path ancestor = path;
        while (ancestor != null) {
            try {
                Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.NoSuchFileException e) {
                missing.addFirst(ancestor.getFileName());
                ancestor = ancestor.getParent();
                continue;
            }
            // 已存在的链接（含目录联接）若无法解析，不能按普通缺失路径回退。
            Path real = ancestor.toRealPath();
            for (Path segment : missing) {
                real = real.resolve(segment);
            }
            return real.normalize();
        }
        throw new IOException("No existing filesystem ancestor: " + path);
    }
}
