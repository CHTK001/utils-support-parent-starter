package com.chua.common.support.task.backup;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 JSONL 文件的备份历史存储
 *
 * <p>每条 {@link BackupHistoryRecord} 序列化为一行 JSON，追加写入
 * {@code {backupDir}/backup-history.jsonl}；查询时从文件尾部倒序读取。
 * 单实例内 {@code synchronized} 保证并发追加行不交错。
 *
 * <p>存储故障（读写失败、单行解析失败）只记录告警，不中断备份流程。
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
public class JsonlBackupHistoryStore implements BackupHistoryStore {

    /**
     * 历史文件名（位于备份根目录）
     */
    public static final String HISTORY_FILE_NAME = "backup-history.jsonl";

    /**
     * 历史文件路径
     */
    private final Path file;

    /**
     * @param backupDir 备份根目录（历史文件与其同目录）
     */
    public JsonlBackupHistoryStore(Path backupDir) {
        this.file = backupDir.resolve(HISTORY_FILE_NAME);
    }

    /**
     * 追加一条记录（单行 JSON + 换行）；目录不存在时自动创建。
     */
    @Override
    public synchronized void append(BackupHistoryRecord record) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.toJson(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[backup] 写入备份历史失败: {}", e.getMessage());
        }
    }

    /**
     * 从文件尾部倒序读取最近 limit 条；损坏行跳过并告警。
     */
    @Override
    public synchronized List<BackupHistoryRecord> list(int limit) {
        if (limit <= 0 || !Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[backup] 读取备份历史失败: {}", e.getMessage());
            return List.of();
        }
        List<BackupHistoryRecord> records = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && records.size() < limit; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                records.add(Json.fromJson(line, BackupHistoryRecord.class));
            } catch (Exception e) {
                log.warn("[backup] 解析备份历史行失败: {}", e.getMessage());
            }
        }
        return Collections.unmodifiableList(records);
    }
}
