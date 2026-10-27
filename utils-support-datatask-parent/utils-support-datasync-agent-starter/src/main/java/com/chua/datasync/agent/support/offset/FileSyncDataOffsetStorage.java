package com.chua.datasync.agent.support.offset;

import com.chua.datasync.agent.support.model.SyncDataOffset;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 文件系统偏移量存储。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FileSyncDataOffsetStorage implements SyncDataOffsetStorage {

    /**
     * 存储目录
    */
    private final Path storageDir;

    /**
     * 创建 文件同步数据偏移量storage 实例
    */
    public FileSyncDataOffsetStorage() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "datasync-offsets"));
    }

    /**
     * 创建 文件同步数据偏移量storage 实例
     * @param storageDir storagedir
     */
    public FileSyncDataOffsetStorage(Path storageDir) {
        this.storageDir = storageDir;
        try {
            Files.createDirectories(storageDir);
        } catch (Exception e) {
            throw new IllegalStateException("创建偏移量存储目录失败: " + storageDir, e);
        }
    }

    /**
     * 读取
    */
    @Override
    public SyncDataOffset read(String sourceId, SyncDataOffset defaultValue) {
        return read(sourceId, null, defaultValue);
    }

    /**
     * 按「源 + 映射」读取偏移量。
     *
     * <p>多目标同步时，同一源会挂多个映射（一个目标一个映射）。若水位只按源存，
     * 两个目标会写到同一个文件里互相覆盖：A 目标推进水位后，B 目标下次
     * 直接从 A 的位置开始读，中间那批数据永久丢失。</p>
     *
     * <p>因此文件名取 {@code <源>__<映射>.offset}；映射为空时退回
     * {@code <源>.offset}，兼容历史数据与单目标场景。</p>
     *
     * @param sourceId     源标识
     * @param mappingId    映射标识，可为空
     * @param defaultValue 默认值
     * @return 偏移量
     */
    public SyncDataOffset read(String sourceId, String mappingId, SyncDataOffset defaultValue) {
        if (sourceId == null) {
            return defaultValue;
        }
        Path file = storageDir.resolve(fileNameOf(sourceId, mappingId));
        if (!Files.exists(file) && mappingId != null && !mappingId.isBlank()) {
            // 兼容升级前只有源级水位的存量数据：映射级文件不存在时回落到源级
            file = storageDir.resolve(fileNameOf(sourceId, null));
        }
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

    /**
     * 水位文件名。
     *
     * @param sourceId  源标识
     * @param mappingId 映射标识，可为空
     * @return 文件名
     */
    private static String fileNameOf(String sourceId, String mappingId) {
        if (mappingId == null || mappingId.isBlank()) {
            return sourceId + ".offset";
        }
        return sourceId + "__" + mappingId + ".offset";
    }

    /**
     * 写入
    */
    @Override
    public void write(SyncDataOffset offset) {
        if (offset == null || offset.sourceId() == null) {
            return;
        }
        // 写入同样按「源 + 映射」分文件，与 read 的键保持一致
        Path file = storageDir.resolve(fileNameOf(offset.sourceId(), offset.mappingId()));
        String line = offset.sourceId() + "," + offset.offsetValue() + "," + offset.timestamp() + "," + offset.mappingId();
        try {
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("写入偏移量失败: " + offset, e);
        }
    }

    /**
     * 删除
    */
    @Override
    public void delete(String sourceId) {
        if (sourceId == null) {
            return;
        }
        Path file = storageDir.resolve(sourceId + ".offset");
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.debug("[datasync] 删除偏移量文件失败，已忽略: {}", e.getMessage());
        }
    }

    /**
     * 列表全部
    */
    @Override
    public java.util.List<SyncDataOffset> listAll() {
        try {
            return Files.list(storageDir)
                    .filter(p -> p.toString().endsWith(".offset"))
                    .map(FileSyncDataOffsetStorage::readFile)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 直接读一个水位文件。
     *
     * <p>不走 {@code read(文件名)}：文件名可能是 {@code <源>__<映射>.offset}，
     * 把文件名当源标识会得到错误结果，这里改为解析文件内容本身。</p>
     *
     * @param file 水位文件
     * @return 偏移量；读不出返回 null
     */
    private static SyncDataOffset readFile(Path file) {
        try {
            String content = Files.readString(file).trim();
            if (content.isEmpty()) {
                return null;
            }
            String[] parts = content.split(",", 4);
            if (parts.length != 4) {
                return null;
            }
            return new SyncDataOffset(parts[0], parts[1], Long.parseLong(parts[2]), parts[3]);
        } catch (Exception e) {
            return null;
        }
    }
}
