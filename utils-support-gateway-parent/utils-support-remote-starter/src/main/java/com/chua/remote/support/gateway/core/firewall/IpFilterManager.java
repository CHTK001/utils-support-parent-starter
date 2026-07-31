package com.chua.remote.support.gateway.core.firewall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 过滤管理器
 * 支持黑名单/白名单模式，数据持久化到磁盘

 * @author CH
 */@Slf4j
public class IpFilterManager {

    /**
     * IP 过滤模式
     * <ul>
     *   <li>BLACKLIST - 黑名单模式，仅封禁列表中的 IP</li>
     *   <li>WHITELIST - 白名单模式，仅允许列表中的 IP</li>
     *   <li>DISABLED  - 禁用过滤，所有 IP 均可访问</li>
     * </ul>
 * @author CH
 */
public enum FilterMode { BLACKLIST, WHITELIST, DISABLED }

    /** 被封禁的 IP 集合（黑名单） */
    private final Set<String> blockedIps = ConcurrentHashMap.newKeySet();
    /** 被允许的 IP 集合（白名单） */
    private final Set<String> allowedIps = ConcurrentHashMap.newKeySet();
    /** IP 封禁原因映射 */
    private final Map<String, String> blockedReasons = new ConcurrentHashMap<>();
    /** IP 封禁时间映射 */
    private final Map<String, Instant> blockedAt = new ConcurrentHashMap<>();
    /** 当前过滤模式，默认为 DISABLED */
    private volatile FilterMode mode = FilterMode.DISABLED;
    /** 持久化文件路径 */
    private final Path dataPath;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 创建默认 IP 过滤管理器，持久化路径为 ~/.remote-gateway/ip-filter.json
     */
    public IpFilterManager() {
        this(Path.of(System.getProperty("user.home"), ".remote-gateway", "ip-filter.json"));
    }

    /**
     * 创建指定持久化路径的 IP 过滤管理器，自动从磁盘加载已有配置
     *
     * @param dataPath 持久化文件路径
     */
    public IpFilterManager(Path dataPath) {
        this.dataPath = dataPath;
        loadFromDisk();
    }

    /**
     * 检查 IP 是否被允许
     * @return true 如果允许访问
     */
    public boolean isAllowed(String ip) {
        if (mode == FilterMode.DISABLED) { return true; }
        if (mode == FilterMode.BLACKLIST) {
            return !blockedIps.contains(ip);
        }
        // WHITELIST 模式
        return allowedIps.contains(ip);
    }

    /**
     * 获取当前过滤模式
     *
     * @return 当前过滤模式
     */
    public FilterMode getMode() { return mode; }

    /**
     * 设置过滤模式并持久化
     *
     * @param mode 目标过滤模式
     */
    public void setMode(FilterMode mode) {
        this.mode = mode;
        saveToDisk();
        log.info("[IpFilter] 模式切换为: {}", mode);
    }

    /**
     * 将 IP 添加到黑名单（封禁）
     *
     * @param ip     要封禁的 IP 地址
     * @param reason 封禁原因
     */
    public void blockIp(String ip, String reason) {
        blockedIps.add(ip);
        blockedReasons.put(ip, reason != null ? reason : "手动封禁");
        blockedAt.put(ip, Instant.now());
        saveToDisk();
        log.info("[IpFilter] 封禁 IP: {} 原因: {}", ip, reason);
    }

    /**
     * 从黑名单移除指定 IP（解封）
     *
     * @param ip 要解封的 IP 地址
     * @return 如果该 IP 确实在黑名单中则返回 true
     */
    public boolean unblockIp(String ip) {
        boolean removed = blockedIps.remove(ip);
        if (removed) {
            blockedReasons.remove(ip);
            blockedAt.remove(ip);
            saveToDisk();
            log.info("[IpFilter] 解封 IP: {}", ip);
        }
        return removed;
    }

    /**
     * 将 IP 添加到白名单
     *
     * @param ip 要允许的 IP 地址
     */
    public void allowIp(String ip) {
        allowedIps.add(ip);
        saveToDisk();
        log.info("[IpFilter] 白名单添加 IP: {}", ip);
    }

    /**
     * 从白名单移除指定 IP
     *
     * @param ip 要移除的 IP 地址
     * @return 如果该 IP 确实在白名单中则返回 true
     */
    public boolean removeAllowedIp(String ip) {
        boolean removed = allowedIps.remove(ip);
        if (removed) {
            saveToDisk();
            log.info("[IpFilter] 白名单移除 IP: {}", ip);
        }
        return removed;
    }

    /** 获取黑名单列表 */
    public List<Map<String, Object>> getBlockedList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String ip : blockedIps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", ip);
            m.put("reason", blockedReasons.getOrDefault(ip, ""));
            m.put("blockedAt", blockedAt.containsKey(ip) ? blockedAt.get(ip).toString() : "");
            list.add(m);
        }
        return list;
    }

    /** 获取白名单列表 */
    public List<String> getAllowedList() {
        return new ArrayList<>(allowedIps);
    }

    /**
     * 获取 IP 过滤器的整体状态
     *
     * @return 包含模式、黑白名单数量的状态 Map
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", mode.name());
        status.put("blockedCount", blockedIps.size());
        status.put("allowedCount", allowedIps.size());
        return status;
    }

    // ===== 持久化 =====

    /**
     * 从磁盘加载 IP 过滤器持久化数据
     * <p>
     * 如果持久化文件不存在则静默忽略。
     * 加载内容包括：过滤模式、黑名单（含原因和时间）、白名单。
     */
    private void loadFromDisk() {
        if (!Files.exists(dataPath)) { return; }
        try {
            String json = Files.readString(dataPath);
            Map<String, Object> data = mapper.readValue(json, new TypeReference<>() {});

            String modeStr = (String) data.get("mode");
            if (modeStr != null) {
                try { mode = FilterMode.valueOf(modeStr); }
 catch (Exception e) { log.debug("解析过滤模式失败: {}", modeStr, e); }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocked = (List<Map<String, Object>>) data.get("blocked");
            if (blocked != null) {
                for (Map<String, Object> entry : blocked) {
                    String ip = (String) entry.get("ip");
                    String reason = (String) entry.get("reason");
                    String atStr = (String) entry.get("blockedAt");
                    if (ip != null) {
                        blockedIps.add(ip);
                        if (reason != null) { blockedReasons.put(ip, reason); }
                        if (atStr != null) {
                            try { blockedAt.put(ip, Instant.parse(atStr)); }
 catch (Exception e) { log.debug("解析封禁时间失败: {}", atStr, e); }
                        }
                    }
                }
            }

            @SuppressWarnings("unchecked")
            List<String> allowed = (List<String>) data.get("allowed");
            if (allowed != null) { allowedIps.addAll(allowed); }

            log.info("[IpFilter] 已加载: mode={}, blocked={}, allowed={}", mode, blockedIps.size(), allowedIps.size());
        }
 catch (IOException e) {
            log.warn("[IpFilter] 读取配置失败: {}", e.getMessage());
        }
    }

    /**
     * 将当前 IP 过滤器状态持久化到磁盘
     * <p>
     * 保存内容包括：过滤模式、黑名单（含原因和时间）、白名单。
     * 如果父目录不存在则自动创建。
     */
    private void saveToDisk() {
        try {
            Files.createDirectories(dataPath.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", mode.name());
            data.put("blocked", getBlockedList());
            data.put("allowed", new ArrayList<>(allowedIps));
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(dataPath, json);
        }
 catch (IOException e) {
            log.warn("[IpFilter] 保存配置失败: {}", e.getMessage());
        }
    }
}
