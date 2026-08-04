package com.chua.common.support.ai.chat.protocol;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NullUnmarked;

/**
 * 基于文件的 AI 令牌提供者实现。
 *
 * <p>令牌文件格式（# 开头为注释）：</p>
 * <pre>{@code
 *   # 格式: token [group] [expire_time(yyyy-MM-dd)]
 *   sk-abc123 default
 *   sk-def456 vip 2026-12-31
 *   sk-ghi789 admin
 * }</pre>
 *
 * <p>文件变更时自动热加载（通过 WatchService），无需重启。
 * CRUD 操作实时更新内存并同步写回文件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class FileAiTokenProvider implements AiTokenProvider, AutoCloseable {

    /** token → AiToken 映射 */
    private final Map<String, AiToken> tokenMap = new ConcurrentHashMap<>();

    /** 令牌文件路径 */
    private final Path filePath;

    /** 令牌文件所在目录 */
    private final Path watchDir;

    /** 是否正在监听文件 */
    private final AtomicBoolean watching = new AtomicBoolean(false);

    /** 文件监听线程 */
    private Thread watcherThread;

    /** 日期格式 */
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    /**
     * 创建文件令牌提供者。
     *
     * @param filePath 令牌文件路径
     */
    public FileAiTokenProvider(String filePath) {
        this(Paths.get(filePath));
    }

    /**
     * 创建文件令牌提供者。
     *
     * @param filePath 令牌文件路径
     */
    public FileAiTokenProvider(Path filePath) {
        this.filePath = filePath.toAbsolutePath().normalize();
        this.watchDir = this.filePath.getParent() != null ? this.filePath.getParent() : Paths.get(".").toAbsolutePath();
        reload();
        startFileWatcher();
    }

    // ======================== 加载 ========================

    /**
     * 从文件加载所有令牌到内存。
     */
    public void reload() {
        doLoadFromFile();
    }

    private synchronized void doLoadFromFile() {
        if (!Files.exists(filePath)) {
            log.warn("[FileAiTokenProvider] 令牌文件不存在，创建空文件: {}", filePath);
            try {
                Files.createDirectories(filePath.getParent());
                Files.createFile(filePath);
            } catch (IOException e) {
                log.warn("[FileAiTokenProvider] 创建令牌文件失败: {}", e.getMessage());
            }
            return;
        }
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            Map<String, AiToken> newMap = new LinkedHashMap<>();
            int count = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                String[] parts = trimmed.split("\\s+");
                String token = parts[0];
                String group = parts.length > 1 ? parts[1] : "default";
                Date expireTime = null;
                if (parts.length > 2) {
                    try {
                        expireTime = dateFormat.parse(parts[2]);
                    } catch (Exception e) {
                        log.warn("[FileAiTokenProvider] 解析过期时间失败: {}", parts[2]);
                    }
                }

                newMap.put(token, AiToken.builder()
                        .token(token)
                        .group(group)
                        .expireTime(expireTime)
                        .enabled(true)
                        .remark("from file: " + filePath.getFileName())
                        .build());
                count++;
            }
            tokenMap.clear();
            tokenMap.putAll(newMap);
            log.info("[FileAiTokenProvider] 从文件加载 {} 个令牌: {}", count, filePath.getFileName());
        } catch (IOException e) {
            log.error("[FileAiTokenProvider] 读取令牌文件失败: {}", e.getMessage());
        }
    }

    /**
     * 将内存中的令牌写回文件。
     */
    private void flushToFile() {
        synchronized (this) {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("# AI Token File");
            lines.add("# format: token [group] [expire_time(yyyy-MM-dd)]");
            lines.add("");

            for (AiToken token : tokenMap.values()) {
                StringBuilder sb = new StringBuilder(token.getToken());
                sb.append(' ').append(token.getGroup() != null ? token.getGroup() : "default");
                if (token.getExpireTime() != null) {
                    sb.append(' ').append(dateFormat.format(token.getExpireTime()));
                }
                lines.add(sb.toString());
            }

            Files.write(filePath, lines, StandardCharsets.UTF_8);
            log.debug("[FileAiTokenProvider] 令牌已写回文件: {} 条", tokenMap.size());
        } catch (IOException e) {
            log.error("[FileAiTokenProvider] 写入令牌文件失败: {}", e.getMessage());
        }
        }
    }

    // ======================== 文件监听 ========================

    /**
     * 启动文件变更监听，文件修改时自动重新加载。
     */
    private void startFileWatcher() {
        if (!watching.compareAndSet(false, true)) return;

        watcherThread = new Thread(() -> {
            try (WatchService watcher = watchDir.getFileSystem().newWatchService()) {
                watchDir.register(watcher,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);

                while (watching.get()) {
                    WatchKey key;
                    try {
                        key = watcher.poll(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (key == null) continue;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed.equals(filePath.getFileName())) {
                            log.info("[FileAiTokenProvider] 文件已变更，重新加载: {}", filePath.getFileName());
                            doLoadFromFile();
                        }
                    }
                    key.reset();
                }
            } catch (Exception e) {
                log.warn("[FileAiTokenProvider] 文件监听停止: {}", e.getMessage());
            }
        }, "file-token-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    /**
     * 停止文件监听并释放资源。
     *
     * <p>调用后此提供者不再响应文件变更。</p>
     */
    public void stopWatching() {
        watching.set(false);
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    /**
     * 释放资源（停止监听）。
     */
    public void close() {
        stopWatching();
        tokenMap.clear();
        log.info("[FileAiTokenProvider] 已关闭: {}", filePath.getFileName());
    }

    // ======================== AiTokenProvider 接口 ========================

    @Override
    public AiToken getValidToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) return null;
        AiToken token = tokenMap.get(tokenValue);
        if (token != null && token.isValid()) {
            return token;
        }
        return null;
    }

    @Override
    public Map<String, AiToken> allTokens() {
        return Map.copyOf(tokenMap);
    }

    @Override
    public int count() {
        return tokenMap.size();
    }

    @Override
    public synchronized void put(AiToken token) {
        if (token == null || token.getToken() == null || token.getToken().isBlank()) return;
        tokenMap.put(token.getToken(), token);
        flushToFile();
        log.debug("[FileAiTokenProvider] 令牌已添加/更新: {}", maskToken(token.getToken()));
    }

    @Override
    public synchronized void putAll(List<AiToken> tokens) {
        if (tokens == null) return;
        for (AiToken token : tokens) {
            if (token.getToken() != null && !token.getToken().isBlank()) {
                tokenMap.put(token.getToken(), token);
            }
        }
        flushToFile();
        log.info("[FileAiTokenProvider] 批量更新 {} 个令牌", tokens.size());
    }

    @Override
    public synchronized AiToken remove(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) return null;
        AiToken removed = tokenMap.remove(tokenValue);
        if (removed != null) {
            flushToFile();
            log.debug("[FileAiTokenProvider] 令牌已删除: {}", maskToken(tokenValue));
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        tokenMap.clear();
        flushToFile();
        log.info("[FileAiTokenProvider] 所有令牌已清空");
    }

    // ======================== 工具 ========================

    /**
     * 脱敏令牌，仅显示前 8 位。
     */
    public static String maskToken(String token) {
        if (token == null) return null;
        if (token.length() <= 8) return token;
        return token.substring(0, 8) + "***";
    }
}