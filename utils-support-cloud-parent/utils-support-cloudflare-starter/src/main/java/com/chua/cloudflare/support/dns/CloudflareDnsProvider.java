package com.chua.cloudflare.support.dns;

import com.chua.cloudflare.support.CloudflareClient;
import com.chua.cloudflare.support.CloudflareConfig;
import com.chua.common.support.network.dns.DnsConfig;
import com.chua.common.support.network.dns.DnsProvider;
import com.chua.common.support.network.dns.DnsRecord;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
* Cloudflare DNS 提供者实现。
*
* <p>基于 Cloudflare API v4 的 DNS 记录管理，实现 {@link DnsProvider} 契约。
* 支持 A/AAAA/TXT/CNAME 等记录的添加、删除与查询，可用于域名解析与 ACME DNS-01 验证。</p>
*
* <p>链式调用示例：</p>
* <pre>{@code
* DnsProvider dns = DnsProvider.create("cloudflare")
*         .config(DnsConfig.builder()
*                 .token("cf-api-token")
*                 .zoneName("example.com")
*                 .build());
*
* dns.addRecord(DnsRecord.builder()
*         .type("TXT")
*         .name("_acme-challenge.example.com")
*         .content("challenge-value")
*         .build());
* }</pre>"TXT")
*         .name("_acme-challenge.example.com")
*         .content("challenge-value")
*         .build());
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @版本 1.0.0
 */
@Slf4j
@Spi("cloudflare")
public class CloudflareDnsProvider implements DnsProvider {

    /**
    * Cloudflare API 客户端
     */
    private CloudflareClient client;

    /**
    * 区域 标识（zoneid），由 zone名称 解析得到
    * @param name 名称
    * @param type 类型
    * @return 列表records的结果
    * @param config 配置
     */
    private String zoneId;

    @Override
    public DnsProvider config(DnsConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DnsConfig must not be null");
        }
        CloudflareConfig cfConfig = new CloudflareConfig()
                .setToken(config.getToken())
                .setBaseUrl(config.getBaseUrl() != null
                        ? config.getBaseUrl()
                        : "https://api.cloudflare.com/client/v4");
        this.client = new CloudflareClient(cfConfig);
        this.zoneId = config.getZoneId() != null
                ? config.getZoneId()
                : resolveZoneId(config.getZoneName());
        return this;
    /**
    * 添加record。
    * @param record record
    * @return 添加record的结果
     */
    }

    @Override
    public DnsProvider addRecord(DnsRecord record) {
        ensureReady();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", record.getType());
        payload.put("name", record.getName());
        payload.put("content", record.getContent());
        payload.put("ttl", record.getTtl());
        if (record.getPriority() != null) {
            payload.put("priority", record.getPriority());
        }
        if ("A".equalsIgnoreCase(record.getType())
                || "AAAA".equalsIgnoreCase(record.getType())
                || "CNAME".equalsIgnoreCase(record.getType())) {
            payload.put("proxied", record.isProxied());
        }
        client.post("/zones/" + zoneId + "/dns_records", payload);
        log.info("Cloudflare DNS 记录添加成功: {} {} -> {}", record.getType(), record.getName(), record.getContent());
        return this;
    /**
    * 移除record。
    * @param record record
    * @return 移除record的结果
    * @param name 名称
    * @param type 类型
     */
    }

    @Override
    public DnsProvider removeRecord(DnsRecord record) {
        ensureReady();
        List<DnsRecord> existing = listRecords(record.getName(), record.getType());
        for (DnsRecord r : existing) {
            if (r.getId() != null) {
                client.call(com.chua.common.support.network.http.HttpMethod.DELETE,
                        "/zones/" + zoneId + "/dns_records/" + r.getId(), null);
            }
        }
        log.info("Cloudflare DNS 记录删除成功: {} {}", record.getType(), record.getName());
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DnsRecord> listRecords(String name, String type) {
        ensureReady();
        StringBuilder path = new StringBuilder("/zones/").append(zoneId).append("/dns_records");
        if (name != null && !name.isEmpty()) {
            path.append("?name=").append(name);
        }
        if (type != null && !type.isEmpty()) {
            path.append(name != null && !name.isEmpty() ? "&" : "?").append("type=").append(type);
        }
        Object result = client.get(path.toString());
        List<DnsRecord> records = new ArrayList<>();
        if (result instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    records.add(toRecord((Map<String, Object>) map));
                }
            }
        }
        return records;
    }

    /**
    * 解析区域 标识。
    *
    * @param zoneName 区域名称（主域名）
    * @return 区域 标识
     */
    @SuppressWarnings("unchecked")
    private String resolveZoneId(String zoneName) {
        if (zoneName == null || zoneName.isEmpty()) {
            throw new IllegalArgumentException("zoneName is required");
        }
        Object result = client.get("/zones?name=" + zoneName);
        if (result instanceof List<?> list && !list.isEmpty()) {
            Object first = list.getFirst();
            if (first instanceof Map<?, ?> map) {
                Object id = map.get("id");
                if (id != null) {
                    return id.toString();
                }
            }
        }
        throw new IllegalStateException("未找到区域: " + zoneName);
    }

    /**
    * 将 Cloudflare 记录 映射 转换为 {@link DnsRecord}。
    *
    * @param map Cloudflare 记录
    * @return DNS 记录
     */
    private DnsRecord toRecord(Map<String, Object> map) {
        DnsRecord.DnsRecordBuilder builder = DnsRecord.builder()
                .name(str(map.get("name")))
                .type(str(map.get("type")))
                .content(str(map.get("content")))
                .id(str(map.get("id")));
        Object ttl = map.get("ttl");
        if (ttl instanceof Number n) {
            builder.ttl(n.intValue());
        }
        Object proxied = map.get("proxied");
        if (proxied instanceof Boolean b) {
            builder.proxied(b);
        }
        return builder.build();
    }

    /**
    * 安全转字符串。
    *
    * @param obj 对象
    * @return 字符串，null 时返回 空
     */
    private String str(Object obj) {
        return obj == null ? null : obj.toString();
    }

    /**
    * 确保客户端与区域已就绪。
     */
    private void ensureReady() {
        if (client == null) {
            throw new IllegalStateException("请先调用 config 配置 DNS 提供者");
        }
        if (zoneId == null || zoneId.isEmpty()) {
            throw new IllegalStateException("zoneId 未解析");
        }
    }
}
