package com.chua.captcha.support;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于文件存储的验证码任务持久化实现
 * <p>
 * 使用纯文本文件存储任务结果，每行一条记录。
 * 格式为：任务id|成功|令牌|消息|错误编码|执行耗时
 * 各字段以转义方式写入（{@code %}、{@code |}、换行符被编码为 {@code %XX}），
 * 因此远端返回的错误描述或任务标识里出现分隔符与换行也不会撑破记录。
 * 内存中维护缓存以减少文件 IO，每次变更后全量原子重写文件。
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
    /**
     * 保存
    */
    public synchronized void save(String taskId, CaptchaResponse response) {
        ensureLoaded();
        cache.put(taskId, response);
        persist();
    }

    @Override
    /**
     * 查询
    */
    public synchronized Optional<CaptchaResponse> query(String taskId) {
        ensureLoaded();
        return Optional.ofNullable(cache.get(taskId));
    }

    @Override
    /**
     * 删除
    */
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
                    String taskId = decode(parts[0]);
                    CaptchaResponse response = CaptchaResponse.builder()
                            .taskId(taskId)
                            .success(Boolean.parseBoolean(parts[1]))
                            .token(decode(parts[2]))
                            .message(decode(parts[3]))
                            .errorCode(decode(parts[4]))
                            .executionTime(parts.length >= 6 ? parseLong(parts[5]) : 0L)
                            .build();
                    cache.put(taskId, response);
                }
            }
        } catch (IOException e) {
            log.error("[FileTaskPersistence] Failed to load tasks from {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * 将缓存全量写回文件：先写同目录临时文件再原子替换，避免进程中断留下半截文件
     */
    private void persist() {
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, CaptchaResponse> entry : cache.entrySet()) {
                    CaptchaResponse r = entry.getValue();
                    writer.write(String.join(SEPARATOR,
                            encode(entry.getKey()),
                            String.valueOf(r.isSuccess()),
                            encode(r.getToken()),
                            encode(r.getMessage()),
                            encode(r.getErrorCode()),
                            String.valueOf(r.getExecutionTime())
                    ));
                    writer.newLine();
                }
            }
            try {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[FileTaskPersistence] Failed to persist tasks to {}: {}", filePath, e.getMessage());
            deleteQuietly(tmp);
        }
    }

    /**
     * 清理写入失败的临时文件
     *
     * @param tmp 临时文件路径
     */
    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 临时文件残留不影响主文件
        }
    }

    /**
     * 解析执行耗时，脏数据按 0 处理
     *
     * @param text 文本
     * @return 毫秒数
     */
    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 编码单字段：转义 {@code %}、{@code |}、{@code \r}、{@code \n}
     *
     * @param value 原值，可为 null
     * @return 可安全写入一行的文本
     */
    private static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '%' -> sb.append("%25");
                case '|' -> sb.append("%7C");
                case '\r' -> sb.append("%0D");
                case '\n' -> sb.append("%0A");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 解码单字段
     *
     * @param value 文件中的文本
     * @return 原值，空文本还原为 null
     */
    private static String decode(String value) {
        if (value.isEmpty()) {
            return null;
        }
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    sb.append((char) (high * 16 + low));
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
