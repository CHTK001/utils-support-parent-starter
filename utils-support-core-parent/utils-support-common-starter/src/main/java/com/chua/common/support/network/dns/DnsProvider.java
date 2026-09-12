package com.chua.common.support.network.dns;

import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
* DNS 服务提供者接口。
*
* <p>统一 DNS 记录管理契约，支持添加、删除、查询 DNS 记录，
* 用于域名解析与 ACME DNS-01 验证等场景。通过 SPI 机制按服务商名称路由，
* 例如 {@code cloudflare}、{@code dnspod}、{@code aliyun} 等。</p>
*
* <p>链式调用示例：</p>
* <pre>{@code
* DnsProvider dns = DnsProvider.create("cloudflare")
*         .config(DnsConfig.builder()
*                 .token("xxx")
*                 .zoneName("example.com")
*                 .build());
*
* // 添加 ACME DNS-01 验证 TXT 记录
* dns.addRecord(DnsRecord.builder()
*         .type("TXT")
*         .name("_acme-challenge.example.com")
*         .content("challenge-value")
*         .build());
*
* // 查询记录
* List<DnsRecord> records = dns.listRecords("_acme-challenge.example.com", "TXT");
*
* // 删除记录
* dns.removeRecord(DnsRecord.builder()
*         .type("TXT")
*         .name("_acme-challenge.example.com")
*         .build());
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
public interface DnsProvider {

    /**
    * 通过 SPI 创建指定服务商的 DNS 提供者实例。
    *
    * @param name 服务商名称（如 cloudflare、dnspod、aliyun）
    * @return DNS 提供者实例，未找到时返回 null
     */
    static DnsProvider create(String name) {
        return ServiceProvider.of(DnsProvider.class).getNewExtension(name);
    }

    /**
    * 配置 DNS 提供者（认证信息、区域等）。
    *
    * @param config DNS 配置
    * @return 当前实例，支持链式调用
     */
    DnsProvider config(DnsConfig config);

    /**
    * 添加一条 DNS 记录。
    *
    * @param record DNS 记录
    * @return 当前实例，支持链式调用
     */
    DnsProvider addRecord(DnsRecord record);

    /**
    * 删除一条 DNS 记录。
    *
    * @param record DNS 记录（按 name + type 匹配）
    * @return 当前实例，支持链式调用
     */
    DnsProvider removeRecord(DnsRecord record);

    /**
    * 查询指定名称和类型的 DNS 记录。
    *
    * @param name 记录名称（完整域名，如 _acme-challenge.example.com）
    * @param type 记录类型（A、AAAA、TXT、CNAME 等）
    * @return 匹配的记录列表，可能为空但不为 null
     */
    List<DnsRecord> listRecords(String name, String type);
}
