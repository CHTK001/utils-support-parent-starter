package com.chua.protocol.support.captcha;

import com.chua.common.support.captcha.CaptchaResponse;
import com.chua.common.support.captcha.TaskPersistence;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 文件任务持久化真实实现。
 */
@Slf4j
public class FileTaskPersistenceSupport implements TaskPersistence {

    private static final String SEPARATOR = "|";
    private final Path filePath;
    private final Map<String, CaptchaResponse> cache = new LinkedHashMap<>();
    private boolean loaded = false;

    public FileTaskPersistenceSupport(String filePath) {
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
                if (line.isEmpty()) {
                    continue;
                }
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
