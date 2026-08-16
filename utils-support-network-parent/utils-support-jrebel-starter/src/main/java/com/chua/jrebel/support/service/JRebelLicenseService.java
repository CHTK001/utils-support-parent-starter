package com.chua.jrebel.support.service;

import com.chua.jrebel.support.sign.JRebelSign;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JRebel 许可证服务。
 * <p>
 * 提供 JRebel/XRebel 许可证的生成和管理功能。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JRebelLicenseService {

    /**
     * 服务器 GUID
     */
    private static final String SERVER_GUID = "a]\\%Qq@Yq/4~}Z^sT";

    /**
     * 默认许可证有效期（天）
     */
    private static final int DEFAULT_VALID_DAYS = 180;

    /**
     * 日期时间格式
     */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));

    /**
     * 签名工具
     */
    private final JRebelSign jRebelSign;

    /**
     * 许可证缓存
     */
    private final Map<String, LicenseInfo> licenseCache = new ConcurrentHashMap<>();

    public JRebelLicenseService() {
        this.jRebelSign = new JRebelSign();
    }

    /**
     * 生成许可证 GUID
     *
     * @param clientId 客户端标识
     * @return GUID
     */
    public String generateGuid(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            return UUID.randomUUID().toString().toUpperCase();
        }
        return UUID.nameUUIDFromBytes(clientId.getBytes()).toString().toUpperCase();
    }

    /**
     * 创建租约响应
     *
     * @param clientRandomness 客户端随机数
     * @param username         用户名
     * @param guid             许可证 GUID
     * @param offline          是否离线
     * @return 租约响应 JSON
     */
    public Map<String, Object> createLeaseResponse(long clientRandomness, String username,
                                                   String guid, boolean offline) {
        Instant now = Instant.now();
        Instant validUntil = now.plusSeconds(DEFAULT_VALID_DAYS * 24 * 3600L);

        String validFromStr = DATE_FORMATTER.format(now);
        String validUntilStr = DATE_FORMATTER.format(validUntil);

        String signature = jRebelSign.toLeaseCreateJson(
                clientRandomness, guid, offline, validFromStr, validUntilStr);

        Map<String, Object> response = new HashMap<>();
        response.put("serverVersion", "3.2.4");
        response.put("serverProtocolVersion", 2);
        response.put("serverGuid", SERVER_GUID);
        response.put("groupType", "managed");
        response.put("statusCode", "SUCCESS");

        response.put("id", 1);
        response.put("licenseType", 1);
        response.put("evaluationLicense", false);
        response.put("signature", signature);

        response.put("serverRandomness", System.currentTimeMillis());
        response.put("seatPoolType", "standalone");
        response.put("statusMessage", null);
        response.put("company", username != null ? username : "JRebel");
        response.put("canGetLease", true);
        response.put("licenseValidFrom", now.toEpochMilli());
        response.put("licenseValidUntil", validUntil.toEpochMilli());

        response.put("offline", offline);
        response.put("validFrom", validFromStr);
        response.put("validUntil", validUntilStr);

        LicenseInfo licenseInfo = new LicenseInfo(guid, username, validFromStr, validUntilStr, offline);
        licenseCache.put(guid, licenseInfo);

        log.info("创建许可证租约: GUID={}, 用户={}, 离线={}", guid, username, offline);
        return response;
    }

    /**
     * 验证租约
     *
     * @param guid 许可证 GUID
     * @return 验证结果
     */
    public Map<String, Object> validateLease(String guid) {
        Map<String, Object> response = new HashMap<>();

        LicenseInfo licenseInfo = licenseCache.get(guid);
        if (licenseInfo != null) {
            response.put("serverVersion", "3.2.4");
            response.put("serverProtocolVersion", 2);
            response.put("serverGuid", SERVER_GUID);
            response.put("groupType", "managed");
            response.put("statusCode", "SUCCESS");
            response.put("company", licenseInfo.getUsername());
            response.put("canGetLease", true);
            response.put("licenseType", 1);
            response.put("evaluationLicense", false);
            response.put("seatPoolType", "standalone");

            log.info("验证许可证租约成功: GUID={}", guid);
        } else {
            response.put("serverVersion", "3.2.4");
            response.put("serverProtocolVersion", 2);
            response.put("serverGuid", SERVER_GUID);
            response.put("groupType", "managed");
            response.put("statusCode", "SUCCESS");
            response.put("company", "JRebel");
            response.put("canGetLease", true);
            response.put("licenseType", 1);
            response.put("evaluationLicense", false);
            response.put("seatPoolType", "standalone");

            log.info("验证许可证租约（新）: GUID={}", guid);
        }

        return response;
    }

    /**
     * 创建 ping 响应
     *
     * @return ping 响应
     */
    public Map<String, Object> createPingResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("serverVersion", "3.2.4");
        response.put("serverProtocolVersion", 2);
        response.put("serverGuid", SERVER_GUID);
        response.put("groupType", "managed");
        response.put("statusCode", "SUCCESS");
        response.put("company", "JRebel");
        response.put("canGetLease", true);
        response.put("licenseType", 1);
        response.put("evaluationLicense", false);
        response.put("seatPoolType", "standalone");
        return response;
    }

    /**
     * 释放租约
     *
     * @param guid 许可证 GUID
     * @return 是否成功
     */
    public boolean releaseLease(String guid) {
        LicenseInfo removed = licenseCache.remove(guid);
        if (removed != null) {
            log.info("释放许可证租约: GUID={}", guid);
            return true;
        }
        return false;
    }

    /**
     * 获取缓存的许可证数量
     *
     * @return 数量
     */
    public int getCachedLicenseCount() {
        return licenseCache.size();
    }

    /**
     * 许可证信息
     */
    public static class LicenseInfo {
        private final String guid;
        private final String username;
        private final String validFrom;
        private final String validUntil;
        private final boolean offline;

        public LicenseInfo(String guid, String username, String validFrom,
                           String validUntil, boolean offline) {
            this.guid = guid;
            this.username = username;
            this.validFrom = validFrom;
            this.validUntil = validUntil;
            this.offline = offline;
        }

        public String getGuid() {
            return guid;
        }

        public String getUsername() {
            return username;
        }

        public String getValidFrom() {
            return validFrom;
        }

        public String getValidUntil()        {
            return validUntil;
        }

        public boolean isOffline() {
            return offline;
        }
    }
}
