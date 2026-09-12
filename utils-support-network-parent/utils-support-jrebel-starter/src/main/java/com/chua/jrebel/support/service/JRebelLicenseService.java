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
   * jrebel 许可证服务。
 * <p>
   * 提供 jrebel/xrebel 许可证的生成和管理功能。
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
     * 操作成功状态码
     */
    private static final String STATUS_SUCCESS = "SUCCESS";

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

    /** 创建 jrebel执照服务 实例 */
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
        response.put("statusCode", STATUS_SUCCESS);

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
            response.put("statusCode", STATUS_SUCCESS);
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
            response.put("statusCode", STATUS_SUCCESS);
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
        response.put("statusCode", STATUS_SUCCESS);
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
     * @author CH
     * @since 4.0.0
     */
    public static class LicenseInfo {
        /**
         * 许可证 GUID
         */
        private final String guid;
        /**
         * 用户名
         */
        private final String username;
        /**
         * 生效时间
         */
        private final String validFrom;
        /**
         * 失效时间
         */
        private final String validUntil;
        /**
         * 是否离线许可证
         */
        private final boolean offline;

        /**
          * 创建 执照信息 实例
         * @param guid guid
         * @param username 用户名
         * @param validFrom valid从
         * @param validUntil validuntil
         * @param offline offline
         */
        public LicenseInfo(String guid, String username, String validFrom,
                           String validUntil, boolean offline) {
            this.guid = guid;
            this.username = username;
            this.validFrom = validFrom;
            this.validUntil = validUntil;
            this.offline = offline;
        }

        /**
         * 获取Guid
         *
         * @return 获取guid的结果
         */
        public String getGuid() {
            return guid;
        }

        /**
         * 获取用户名
         *
         * @return 获取用户名的结果
         */
        public String getUsername() {
            return username;
        }

        /**
         * 获取Valid从创建
         *
         * @return 获取valid从的结果
         */
        public String getValidFrom() {
            return validFrom;
        }

        /**
         * 获取validuntil
         *
         * @return 获取validuntil的结果
         */
        public String getValidUntil()        {
            return validUntil;
        }

        /**
         * 是否Offline
         *
         * @return 是否offline的结果
         */
        public boolean isOffline() {
            return offline;
        }
    }
}
