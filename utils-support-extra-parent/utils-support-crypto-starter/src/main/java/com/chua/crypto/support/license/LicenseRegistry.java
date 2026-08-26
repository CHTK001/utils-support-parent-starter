package com.chua.crypto.support.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验服务器注册表：指纹 → 注册的私钥封装块
 *
 * <p>持久化格式（文本，一行一条）：
 * <pre>fingerprintHex=base64(私钥封装块)</pre>
 *
 * @author CH
 * @since 2026-08-26
 */
public final class LicenseRegistry {

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
    private LicenseRegistry(Path file) {
        this.file = file;
    }

    /**
     * 从文件加载注册表（不存在则为空表）
     *
     * @param file 注册表文件
     * @return 注册表
     * @throws IOException 读取失败
     */
    public static LicenseRegistry load(Path file) throws IOException {
        LicenseRegistry registry = new LicenseRegistry(file);
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
     * 注册/更新指纹对应的私钥封装块并落盘
     *
     * @param fingerprint 指纹十六进制串
     * @param blob        私钥封装块
     * @throws IOException 写入失败
     */
    public synchronized void register(String fingerprint, byte[] blob) throws IOException {
        store.put(fingerprint, Base64.getMimeEncoder().encodeToString(blob));
        persist();
    }

    /**
     * 吊销指纹并落盘
     *
     * @param fingerprint 指纹
     * @return true 表示存在且已移除
     * @throws IOException 写入失败
     */
    public synchronized boolean revoke(String fingerprint) throws IOException {
        boolean removed = store.remove(fingerprint) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    /**
     * 查询注册的私钥封装块
     *
     * @param fingerprint 指纹
     * @return 封装块字节；未注册返回 null
     */
    public byte[] lookup(String fingerprint) {
        String base64 = store.get(fingerprint);
        return base64 == null ? null : Base64.getMimeDecoder().decode(base64);
    }

    /**
     * 是否已注册
     *
     * @param fingerprint 指纹
     * @return true 表示已注册
     */
    public boolean contains(String fingerprint) {
        return store.containsKey(fingerprint);
    }

    /**
     * 全部注册项快照
     *
     * @return 只读映射
     */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(store);
    }

    /**
     * 持久化
     *
     * @throws IOException 写入失败
     */
    private void persist() throws IOException {
        StringBuilder sb = new StringBuilder();
        store.forEach((fp, blob) -> sb.append(fp).append('=').append(blob).append(System.lineSeparator()));
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
