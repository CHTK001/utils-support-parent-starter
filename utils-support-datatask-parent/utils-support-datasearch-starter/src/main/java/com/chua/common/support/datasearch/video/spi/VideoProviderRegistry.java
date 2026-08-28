package com.chua.common.support.datasearch.video.spi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频资源提供者状态注册表
 * 用于标记被封/过期的站点，避免重复请求
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class VideoProviderRegistry {

    private static final ConcurrentHashMap<String, BlockReason> BLOCKED_PROVIDERS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VideoProviderRegistry() {}

    public enum BlockReason {
        BLOCKED("被封禁"),
        RATE_LIMITED("频率限制"),
        API_CHANGED("API变更"),
        TIMEOUT("超时"),
        UNREACHABLE("不可达");

        private final String label;
        BlockReason(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static class BlockedEntry {
        @JsonProperty("name")
        private String name;
        @JsonProperty("reason")
        private String reason;
        @JsonProperty("blockedAt")
        private String blockedAt;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getBlockedAt() { return blockedAt; }
        public void setBlockedAt(String blockedAt) { this.blockedAt = blockedAt; }
    }

    /** 初始化已封禁列表（从资源文件） */
    public static void initBlockedResources() {
        try {
            InputStream is = VideoProviderRegistry.class.getClassLoader()
                    .getResourceAsStream("blocked-providers.json");
            if (is == null) { System.out.println("[Registry] blocked-providers.json NOT FOUND"); return; }
            byte[] bytes = is.readAllBytes();
            Root root = MAPPER.readValue(bytes, Root.class);
            if (root != null && root.blocked != null) {
                for (BlockedEntry entry : root.blocked) {
                    if (entry.getName() != null) {
                        BlockReason reason = BlockReason.RATE_LIMITED;
                        try { reason = BlockReason.valueOf(entry.getReason()); } catch (Exception ignored) {}
                        BLOCKED_PROVIDERS.put(entry.getName(), reason);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Registry] init failed: " + e.getMessage());
        }
    }

    /** 标记 provider 为被封 */
    public static void block(String name, BlockReason reason) {
        BLOCKED_PROVIDERS.put(name, reason);
    }

    /** 标记 provider 为被封（带自定义原因） */
    public static void block(String name, BlockReason reason, String detail) {
        BLOCKED_PROVIDERS.put(name, reason);
    }

    /** 判断 provider 是否被封 */
    public static boolean isBlocked(String name) {
        return BLOCKED_PROVIDERS.containsKey(name);
    }

    /** 获取被封原因 */
    public static BlockReason getBlockReason(String name) {
        return BLOCKED_PROVIDERS.get(name);
    }

    /** 获取所有被封 provider 名称 */
    public static Set<String> getBlockedNames() {
        return Collections.unmodifiableSet(BLOCKED_PROVIDERS.keySet());
    }

    /** 解除封禁 */
    public static void unblock(String name) {
        BLOCKED_PROVIDERS.remove(name);
    }

    /** 清除所有封禁记录 */
    public static void clear() {
        BLOCKED_PROVIDERS.clear();
    }

    public static class Root {
        @JsonProperty("blocked")
        private List<BlockedEntry> blocked;
        public List<BlockedEntry> getBlocked() { return blocked; }
        public void setBlocked(List<BlockedEntry> blocked) { this.blocked = blocked; }
    }
}
