package com.chua.crypto.support.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于文本文件的注册表实现（单机默认实现）
 *
 * <p>持久化格式（UTF-8，一行一条）：
 * <pre>fingerprintHex=base64(私钥封装块)</pre>
 *
 * <p>写入采用 临时文件 + 原子移动 防止写坏；进程内并发由 synchronized 保证，
 * 跨进程并发写同一文件需部署侧避免。多实例/DB 场景请自行实现 {@link LicenseRegistry}。
 *
 * @author CH
 * @since 2026-08-26
 */
public final class FileLicenseRegistry implements LicenseRegistry {

    /**
     * 内存注册表：指纹(hex) -> Base64(封装块)
     */
    private final Map<String, String> store = new ConcurrentHashMap<>();

    /**
     * 持久化文件
     */
    private final Path file;

    /**
     * 私有构造，经 {@link #load(Path)} 创建
     *
     * @param file 持久化文件
     */
    private FileLicenseRegistry(Path file) {
        this.file = file;
    }

    /**
     * 从文件加载注册表（不存在则为空表）
     *
     * @param file 注册表文件
     * @return 注册表
     * @throws IOException 读取失败
     */
    public static FileLicenseRegistry load(Path file) throws IOException {
        FileLicenseRegistry registry = new FileLicenseRegistry(file);
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    registry.store.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }
        return registry;
    }

    /**
     * 注册/更新并原子落盘
     */
    @Override
    public synchronized void register(String fingerprint, byte[] blob) {
        store.put(fingerprint, Base64.getMimeEncoder().encodeToString(blob));
        persist();
    }

    /**
     * 吊销并落盘
     */
    @Override
    public synchronized boolean revoke(String fingerprint) {
        boolean removed = store.remove(fingerprint) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    /**
     * 查询私钥封装块
     */
    @Override
    public byte[] lookup(String fingerprint) {
        String base64 = store.get(fingerprint);
        return base64 == null ? null : Base64.getMimeDecoder().decode(base64);
    }

    /**
     * 是否已注册
     */
    @Override
    public boolean contains(String fingerprint) {
        return store.containsKey(fingerprint);
    }

    /**
     * 快照
     */
    @Override
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(store);
    }

    /**
     * 原子持久化：临时文件 + 原子移动（失败回退普通移动）
     *
     * @throws IOException 写入失败
     */
    private void persist() throws IOException {
        StringBuilder sb = new StringBuilder();
        store.forEach((fp, blob) -> sb.append(fp).append('=').append(blob).append(System.lineSeparator()));
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "lic", ".tmp");
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
