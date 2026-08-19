package com.chua.common.support.file.tar;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文件权限处理工具类。
 * <p>
 * 用于获取和处理文件的权限信息，支持POSIX系统和非POSIX系统（如Windows）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PermissionUtils {

    /**
     * 标准文件权限枚举。
     * <p>
     * 在非POSIX系统中，所有者和组被视为具有相同的权限，其他人没有权限。
     * </p>
     */
    private static enum StandardFilePermission {
        /**
         * 执行权限模式：0110 (八进制)。
         */
        EXECUTE(0110),
        /**
         * 写入权限模式：0220 (八进制)。
         */
        WRITE(0220),
        /**
         * 读取权限模式：0440 (八进制)。
         */
        READ(0440);

        /** 文件权限模式 */
        private final int mode;

        StandardFilePermission(int mode) {
            this.mode = mode;
        }
    }

    /**
     * POSIX权限到整数的映射表。
     */
    private static final Map<PosixFilePermission, Integer> POSIX_PERMISSION_TO_INTEGER = new HashMap<>();

    static {
        // 所有者权限
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.OWNER_EXECUTE, 0100);
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.OWNER_WRITE, 0200);
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.OWNER_READ, 0400);

        // 组权限
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.GROUP_EXECUTE, 0010);
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.GROUP_WRITE, 0020);
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.GROUP_READ, 0040);

        // 其他人权限
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.OTHERS_EXECUTE, 0001);
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.OTHERS_WRITE, 0002);
        POSIX_PERMISSION_TO_INTEGER.put(PosixFilePermission.OTHERS_READ, 0004);
    }

    /**
     * 判断当前系统是否支持POSIX文件属性视图。
     */
    private static final boolean IS_POSIX = FileSystems.getDefault()
            .supportedFileAttributeViews()
            .contains("posix");

    /**
     * 获取文件的权限（以八进制整数形式表示，例如0755）。
     * <p>
     * 注意：如果操作系统支持POSIX权限，则使用 {@link Files#getPosixFilePermissions} 精确获取；
     * 否则回退到使用标准的Java文件操作（如 {@link File#canExecute()}）。
     * 在回退模式下，'所有者'和'组'的权限被视为相同，'其他人'没有权限。
     * 例如，在Windows上如果文件是'只读'，权限将返回为0550。
     * </p>
     *
     * @param f 要检查的文件对象，不能为null。
     * @return 文件的权限值（八进制整数）。
     * @throws NullPointerException 如果文件为null。
     * @throws IllegalArgumentException 如果文件不存在。
     */
    public static int permissions(File f) {
        if (f == null) {
            throw new NullPointerException("File is null.");
        }
        if (!f.exists()) {
            throw new IllegalArgumentException("File " + f + " does not exist.");
        }

        if (IS_POSIX) {
            return posixPermissions(f);
        } else {
            return standardPermissions(f);
        }
    }

    /**
     * 获取POSIX系统的文件权限。
     *
     * @param f 文件对象。
     * @return 权限的整数值。
     * @throws RuntimeException 如果在获取权限时发生IO异常。
     */
    private static int posixPermissions(File f) {
        int number = 0;
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(f.toPath());
            for (Map.Entry<PosixFilePermission, Integer> entry : POSIX_PERMISSION_TO_INTEGER.entrySet()) {
                if (permissions.contains(entry.getKey())) {
                    number += entry.getValue();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to get POSIX file permissions", e);
        }
        return number;
    }

    /**
     * 读取标准文件权限（适用于非POSIX系统）。
     *
     * @param f 文件对象。
     * @return 包含标准权限的集合。
     */
    private static Set<StandardFilePermission> readStandardPermissions(File f) {
        Set<StandardFilePermission> permissions = new HashSet<>();
        if (f.canExecute()) {
            permissions.add(StandardFilePermission.EXECUTE);
        }
        if (f.canWrite()) {
            permissions.add(StandardFilePermission.WRITE);
        }
        if (f.canRead()) {
            permissions.add(StandardFilePermission.READ);
        }
        return permissions;
    }

    /**
     * 计算标准文件权限的整数值。
     *
     * @param f 文件对象。
     * @return 权限的整数值。
     */
    private static int standardPermissions(File f) {
        int number = 0;
        Set<StandardFilePermission> permissions = readStandardPermissions(f);
        for (StandardFilePermission permission : permissions) {
            number += permission.mode;
        }
        return number;
    }
}
