package com.chua.common.support.config.loader;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 本地文件系统实现的配置保存/加载器。
 *
 * <p>将配置数据以文件形式存储在本地磁盘上。默认根路径为 ${user.home}/.config。
 * 支持原子写入（先写临时文件再移动），确保写入操作的完整性。
 * 具有路径逃逸检查，防止 key 中包含 ../ 等路径穿越攻击。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@Spi({"file", "local"})
@SpiOrder(100)
public class FileConfigSaveOrLoader extends AbstractConfigSaveOrLoader {

    private final Path rootPath;

    public FileConfigSaveOrLoader() {
        this(ConfigSaveLoadSetting.builder().build());
    }

    public FileConfigSaveOrLoader(ConfigSaveLoadSetting setting) {
        super(setting);
        this.rootPath = Paths.get(this.setting.getRootPath()).toAbsolutePath().normalize();
    }

    @Override
    public ConfigSaveResult saveBytes(String key, byte[] content) {
        String normalizedKey = normalizeKey(key);
        byte[] bytes = content == null ? new byte[0] : content;
        try {
            Path target = resolve(normalizedKey);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = target.resolveSibling(target.getFileName() + ".tmp." + System.nanoTime());
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return success(normalizedKey, target.toString(), bytes.length);
        } catch (Exception e) {
            return failure(normalizedKey, e.getMessage());
        }
    }

    @Override
    public Optional<byte[]> loadBytes(String key) {
        String normalizedKey = normalizeKey(key);
        try {
            Path target = resolve(normalizedKey);
            if (!Files.exists(target) || Files.isDirectory(target)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config: " + normalizedKey, e);
        }
    }

    @Override
    public boolean delete(String key) {
        String normalizedKey = normalizeKey(key);
        try {
            return Files.deleteIfExists(resolve(normalizedKey));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete config: " + normalizedKey, e);
        }
    }

    private Path resolve(String key) {
        Path target = rootPath.resolve(key).normalize();
        if (!target.startsWith(rootPath)) {
            throw new IllegalArgumentException("config key escapes root path: " + key);
        }
        return target;
    }
}