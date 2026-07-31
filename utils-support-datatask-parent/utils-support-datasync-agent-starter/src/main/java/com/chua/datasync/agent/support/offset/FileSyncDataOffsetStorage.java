package com.chua.datasync.agent.support.offset;

import com.chua.datasync.agent.support.model.SyncDataOffset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件系统偏移量存储。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FileSyncDataOffsetStorage implements SyncDataOffsetStorage {

    private final Path storageDir;

    public FileSyncDataOffsetStorage() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "datasync-offsets"));
    }

    public FileSyncDataOffsetStorage(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (Exception e) {
            throw new IllegalStateException("创建偏移量存储目录失败: " + storageDir, e);
        }
    }

    @Override
    public SyncDataOffset read(String sourceId, SyncDataOffset defaultValue) {
        if (sourceId == null) {
            return defaultValue;
        }
        Path file = storageDir.resolve(sourceId + ".offset");
        if (!Files.exists(file)) {
            return defaultValue;
        }
        try {
            String content = Files.readString(file).trim();
            if (content.isEmpty()) {
                return defaultValue;
            }
            String[] parts = content.split(",", 4);
            if (parts.length != 4) {
                return defaultValue;
            }
            return new SyncDataOffset(parts[0], parts[1], Long.parseLong(parts[2]), parts[3]);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    public void write(SyncDataOffset offset) {
        if (offset == null || offset.sourceId() == null) {
            return;
        }
        Path file = storageDir.resolve(offset.sourceId() + ".offset");
        String line = offset.sourceId() + "," + offset.offsetValue() + "," + offset.timestamp() + "," + offset.mappingId();
        try {
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("写入偏移量失败: " + offset, e);
        }
    }

    @Override
    public void delete(String sourceId) {
        if (sourceId == null) {
            return;
        }
        Path file = storageDir.resolve(sourceId + ".offset");
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }

    @Override
    public java.util.List<SyncDataOffset> listAll() {
        try {
            return Files.list(storageDir)
                    .filter(p -> p.toString().endsWith(".offset"))
                    .map(p -> read(p.getFileName().toString().replace(".offset", ""), null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }
}