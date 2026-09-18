package com.chua.crypto.support.store;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
* 密钥文件路径解析器
*
* <p>为多种运行形态（普通 Java、SpringBoot、FatJar、Native 等）提供一致的密钥文件定位规则，
* 解析顺序：
* <ol>
*   <li>未配置 → 默认 {@code {user.home}/.chua/crypto/master.key}</li>
*   <li>绝对路径 → 直接使用</li>
*   <li>相对路径 → 依次尝试：工作目录 → 可执行 Jar 所在目录（FatJar 场景）→ 用户主目录；
*       均不存在时取第一个可创建候选（工作目录）</li>
* </ol>
*
* @author CH
* @since 2026-08-26
 */
public final class KeyFileResolver {

    /**
    * 默认密钥文件相对目录
    */
    private static final String DEFAULT_DIR = ".chua/crypto";

    /**
    * 默认密钥文件名
    */
    private static final String DEFAULT_FILE = "master.key";

    /**
    * 私有构造
    */
    private KeyFileResolver() {
    }

    /**
    * 解析密钥文件最终路径
    *
    * @param configured 配置的路径（允许 空/空白）
    * @return 规范化绝对路径
    */
    public static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return defaultKeyFile();
        }
        Path configuredPath = Paths.get(configured);
        if (configuredPath.isAbsolute()) {
            return configuredPath.toAbsolutePath().normalize();
        }
        List<Path> candidates = candidates(configuredPath);
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        // 全部不存在时，落在工作目录下（首次生成场景）
        return candidates.getFirst().toAbsolutePath().normalize();
    }

    /**
    * 默认密钥文件路径
    *
    * @return {user.home}/.chua/crypto/master.key
    */
    public static Path defaultKeyFile() {
        return Paths.get(System.getProperty("user.home"), DEFAULT_DIR, DEFAULT_FILE)
                .toAbsolutePath().normalize();
    }

    /**
    * 构建相对路径候选列表
    *
    * @param relative 相对路径
    * @return 候选列表（工作目录、Jar 目录、用户目录）
    */
    private static List<Path> candidates(Path relative) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(System.getProperty("user.dir")).resolve(relative));
        Path jarDir = jarDirectory();
        if (jarDir != null) {
            candidates.add(jarDir.resolve(relative));
        }
        candidates.add(Paths.get(System.getProperty("user.home")).resolve(relative));
        return candidates;
    }

    /**
    * 获取当前应用 Jar 所在目录（fatjar 支持核心逻辑）
    *
    * <p>兼容三种形态：
    * <ul>
    *   <li>IDE/展开目录运行 — classes 目录本身</li>
    *   <li>普通 FatJar（java -jar）— Jar 文件的父目录</li>
    *   <li>嵌套结构（如 SpringBoot nested URL）— 截断 "!" 后解析</li>
    * </ul>
    *
    * @return Jar 目录；无法确定时返回 空
    */
    static Path jarDirectory() {
        try {
            URI location = KeyFileResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String raw = location.toString();
            int bang = raw.indexOf('!');
            if (bang >= 0) {
                raw = raw.substring(0, bang);
            }
            if (raw.startsWith("jar:")) {
                raw = raw.substring("jar:".length());
            }
            if (!raw.startsWith("file:")) {
                return null;
            }
            Path path = Paths.get(URI.create(raw));
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * 确保父目录存在
    *
    * @param file 目标文件
    * @throws IOException 目录创建失败时抛出
    */
    public static void ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
