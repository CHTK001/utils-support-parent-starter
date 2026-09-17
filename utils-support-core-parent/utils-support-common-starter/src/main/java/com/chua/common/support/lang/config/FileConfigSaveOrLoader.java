package com.chua.common.support.lang.config;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
* 本地文件实现的配置保存和加载器。
* 该类负责将配置数据以字节数组的形式存储在本地文件系统中，并支持读取和删除操作。
* @author CH
* @since 4.0.0.42
 */@Spi({"file", "local"})
@SpiOrder(100)
public class FileConfigSaveOrLoader extends AbstractConfigSaveOrLoader {

    /**
    * 配置文件的根目录路径
    */
    private final Path rootPath;

    /**
    * 默认构造函数，使用默认的配置文件设置。
    */
    public FileConfigSaveOrLoader() {
        this(ConfigSaveLoadSetting.builder().build());
    }

    /**
    * 带参数的构造函数，使用指定的配置设置初始化。
    *
    * @param setting 配置设置对象，包含根路径等参数。
    */
    public FileConfigSaveOrLoader(ConfigSaveLoadSetting setting) {
        super(setting);
        // 获取根路径并将其转换为绝对路径并进行规范化处理，防止路径遍历攻击
        this.rootPath = Paths.get(this.setting.getRootPath()).toAbsolutePath().normalize();
    }

    /**
    * 将字节数组内容保存到指定键对应的文件中。
    * 采用临时文件写入后原子移动的方式，确保写入过程的原子性和安全性。
    *
    * @param key     配置的键名。
    * @param content 要保存的字节数组内容；如果为 null，则保存空字节数组。
    * @return 保存结果对象，包含成功/失败状态及详细信息。
    */
    @Override
    public ConfigSaveResult saveBytes(String key, byte[] content) {
        String normalizedKey = normalizeKey(key);
        byte[] bytes = content == null ? new byte[0] : content;
        try {
            // 解析目标文件路径
            Path target = resolve(normalizedKey);
            Path parent = target.getParent();
            // 如果父目录不存在，则创建父目录
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 创建一个带有时间戳的临时文件，避免并发写入冲突
            Path temp = target.resolveSibling(target.getFileName() + ".tmp." + System.nanoTime());
            // 将数据写入临时文件
            Files.write(temp, bytes);
            try {
                // 尝试原子移动临时文件到目标位置，替换已存在的文件
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                // 如果系统不支持原子移动（如跨文件系统），则回退到非原子移动
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return success(normalizedKey, target.toString(), bytes.length);
        } catch (Exception e) {
            return failure(normalizedKey, e.getMessage());
        }
    }

    /**
    * 从指定键对应的文件中读取字节数组内容。
    *
    * @param key 配置的键名。
    * @return 包含读取到的字节数组的 Optional 对象；如果文件不存在或不是文件，则返回空 Optional。
    */
    @Override
    public Optional<byte[]> loadBytes(String key) {
        String normalizedKey = normalizeKey(key);
        try {
            Path target = resolve(normalizedKey);
            // 检查文件是否存在且不是目录
            if (!Files.exists(target) || Files.isDirectory(target)) {
                return Optional.empty();
            }
            // 读取所有字节并包装在 Optional 中返回
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config: " + normalizedKey, e);
        }
    }

    /**
    * 删除指定键对应的配置文件。
    *
    * @param key 配置的键名。
    * @return 如果文件成功删除则返回 true，否则返回 false。
    */
    @Override
    public boolean delete(String key) {
        String normalizedKey = normalizeKey(key);
        try {
            // 删除文件并返回删除是否成功的状态
            return Files.deleteIfExists(resolve(normalizedKey));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete config: " + normalizedKey, e);
        }
    }

    /**
    * 根据给定的键解析出完整的文件路径，并进行安全校验。
    * 确保解析出的路径不会逃逸到根目录之外，防止路径遍历漏洞。
    *
    * @param key 配置的键名。
    * @return 解析后的绝对路径对象。
    * @throws IllegalArgumentException 如果解析后的路径逃逸了根目录。
    */
    private Path resolve(String key) {
        // 拼接根路径和键名，并进行规范化
        Path target = rootPath.resolve(key).normalize();
        // 安全检查：确保目标路径仍然以根路径开头
        if (!target.startsWith(rootPath)) {
            throw new IllegalArgumentException("config key escapes root path: " + key);
        }
        return target;
    }
}
