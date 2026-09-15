package com.chua.common.support.datasearch.video.spi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public final class VideoProviderRegistry {

    private static final ConcurrentHashMap<String, BlockReason> BLOCKED_PROVIDERS = new ConcurrentHashMap<>(); // blocked提供者
    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /** 视频提供者registry。 */
    private VideoProviderRegistry() {}

    /**
     * 构造函数中自动加载封禁名单（资源文件缺失时忽略）。
     */
    static {
        initBlockedResources();
    }
    /**
    * blockReasonML枚举。
    *
    * @author CH
    * @since 4.0.0
     */

    public enum BlockReason {
        BLOCKED("被封禁"),
        RATE_LIMITED("频率限制"),
        API_CHANGED("API变更"),
        TIMEOUT("超时"),
        UNREACHABLE("不可达");

        private final String label;
        BlockReason(String label) { this.label = label; }
        /**
        * 标签。
        * @return 标签的结果
        * @author CH
        * @since 4.0.0
         */
        public String label() { return label; }
    }

    public static class BlockedEntry {
        @JsonProperty("name")
        private String name;
        @JsonProperty("reason")
        private String reason;
        @JsonProperty("blockedAt")
        private String blockedAt;
        @JsonProperty("note")
        private String note;

        /**
         * 获取名称。
         * @return 获取名称的结果
         */
        public String getName() { return name; }
        /**
         * 设置名称。
         * @param name 名称
         */
        public void setName(String name) { this.name = name; }
        /**
         * 获取ReasonML。
         * @return 获取ReasonML的结果
         */
        public String getReason() { return reason; }
        /**
         * 设置ReasonML。
         * @param reason 原因
         */
        public void setReason(String reason) { this.reason = reason; }
        /**
         * 获取blockedat。
         * @return 获取blockedat的结果
         */
        public String getBlockedAt() { return blockedAt; }
        /**
         * 设置blockedat。
         * @param blockedAt 封禁时间
         */
        public void setBlockedAt(String blockedAt) { this.blockedAt = blockedAt; }
        /**
         * 获取备注。
         * @return 获取备注的结果
         */
        public String getNote() { return note; }
        /**
         * 设置备注。
         * @param note 备注
         */
        public void setNote(String note) { this.note = note; }
    }

    /** 初始化已封禁列表（从资源文件） */
    public static void initBlockedResources() {
        try {
            InputStream is = VideoProviderRegistry.class.getClassLoader()
                    .getResourceAsStream("blocked-providers.json");
            if (is == null) { log.warn("[Registry] blocked-providers.json NOT FOUND"); return; }
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
            log.error("[Registry] init failed: {}", e.getMessage());
        }
    }

    /**
    * 标记 提供者 为被封
    *
    * @param name 名称
    * @param reason ReasonMLML
     */
    public static void block(String name, BlockReason reason) {
        BLOCKED_PROVIDERS.put(name, reason);
    }

    /**
    * 标记 提供者 为被封（带自定义原因）
    *
    * @param name 名称
    * @param reason ReasonMLML
    * @param detail detail
     */
    public static void block(String name, BlockReason reason, String detail) {
        BLOCKED_PROVIDERS.put(name, reason);
    }

    /**
    * 判断 提供者 是否被封
    *
    * @param name 名称
    * @return 是否blocked的结果
     */
    public static boolean isBlocked(String name) {
        return BLOCKED_PROVIDERS.containsKey(name);
    }

    /**
    * 获取被封原因
    *
    * @param name 名称
    * @return 获取blockReasonML的结果
     */
    public static BlockReason getBlockReason(String name) {
        return BLOCKED_PROVIDERS.get(name);
    }

    /**
    * 获取所有被封 提供者 名称
    *
    * @return 获取blocked名称的结果
     */
    public static Set<String> getBlockedNames() {
        return Collections.unmodifiableSet(BLOCKED_PROVIDERS.keySet());
    }

    /**
    * 解除封禁
    *
    * @param name 名称
     */
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
        /**
        * 获取blocked。
        * @return 获取blocked的结果
         */
        public List<BlockedEntry> getBlocked() { return blocked; }
        /**
        * 设置blocked。
        * @param blocked blocked
         */
        public void setBlocked(List<BlockedEntry> blocked) { this.blocked = blocked; }
    }
}
