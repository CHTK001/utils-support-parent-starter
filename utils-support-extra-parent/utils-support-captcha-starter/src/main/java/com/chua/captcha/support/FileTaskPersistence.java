package com.chua.captcha.support;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于文件存储的验证码任务持久化实现
 * <p>
 * 使用纯文本文件存储任务结果，每行一条记录。
 * 格式为：taskId|success|token|message|errorCode
 * 内存中维护缓存以减少文件 IO，每次变更后全量写回文件。
 * </p>
 *
 * @author CH
 * @since 2026-03-16
 */
@Slf4j
public class FileTaskPersistence implements TaskPersistence {

    /**
     * 字段分隔符
     */
    private static final String SEPARATOR = "|";

    /**
     * 持久化文件路径
     */
    private final Path filePath;

    /**
     * 内存缓存（保持写入顺序）
     */
    private final Map<String, CaptchaResponse> cache = new LinkedHashMap<>();

    /**
     * 是否已从文件加载到缓存
     */
    private boolean loaded = false;

    /**
     * 构造文件持久化实例
     *
     * @param filePath 持久化文件路径
     */
    public FileTaskPersistence(String filePath) {
        this.filePath = Paths.get(filePath);
    }

    @Override
    public synchronized void save(String taskId, CaptchaResponse response) {
        ensureLoaded();
        cache.put(taskId, response);
        persist();
    }

    @Override
    public synchronized Optional<CaptchaResponse> query(String taskId) {
        ensureLoaded();
        return Optional.ofNullable(cache.get(taskId));
    }

    @Override
    public synchronized void delete(String taskId) {
        ensureLoaded();
        cache.remove(taskId);
        persist();
    }

    /**
     * 从文件加载缓存（仅首次调用生效）
     */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(filePath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) { continue; }
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 5) {
                    String taskId = parts[0];
                    boolean success = Boolean.parseBoolean(parts[1]);
                    String token = parts[2].isEmpty() ? null : parts[2];
                    String message = parts[3].isEmpty() ? null : parts[3];
                    String errorCode = parts[4].isEmpty() ? null : parts[4];
                    cache.put(taskId, CaptchaResponse.builder()
                            .taskId(taskId)
                            .success(success)
                            .token(token)
                            .message(message)
                            .errorCode(errorCode)
                            .build());
                }
            }
        } catch (IOException e) {
            log.error("[FileTaskPersistence] Failed to load tasks from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * 将缓存全量写回文件
     */
    private void persist() {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, CaptchaResponse> entry : cache.entrySet()) {
                    CaptchaResponse r = entry.getValue();
                    writer.write(String.join(SEPARATOR,
                            entry.getKey(),
                            String.valueOf(r.isSuccess()),
                            r.getToken() != null ? r.getToken() : "",
                            r.getMessage() != null ? r.getMessage() : "",
                            r.getErrorCode() != null ? r.getErrorCode() : ""
                    ));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            log.error("[FileTaskPersistence] Failed to persist tasks to {}: {}", filePath, e.getMessage());
        }
    }
}
