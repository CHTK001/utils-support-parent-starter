package com.chua.common.support.network.dns;

import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
 * DNS 解析客户端 SPI。
 *
 * <p>面向「查询」场景：把域名解析为记录集（A/AAAA/TXT/CNAME/MX/SRV/NS/SOA 等）。
 * 与 {@link DnsProvider}（记录管理，如 Cloudflare API）互补，与
 * {@link DnsServerProvider}（内嵌 DNS 服务端）构成 DNS 三件套。</p>
 *
 * <p>通过 SPI 路由实现：{@code jndi}（默认，JDK 零依赖）；
 * 可按需扩展 {@code dnsjava}、{@code netty-dns}、{@code doh}（DNS over HTTPS）等。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * DnsResolverProvider resolver = DnsResolverProvider.create("jndi")
 *         .config(DnsConfig.builder()
 *                 .baseUrl("dns://223.5.5.5")   // 可选，缺省用系统解析
 *                 .build());
 * List<DnsRecord> records = resolver.resolve("example.com", "A");
 * records.forEach(r -> System.out.println(r.getName() + " -> " + r.getContent()));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface DnsResolverProvider {

    /**
     * 通过 SPI 创建指定名称的解析器实例。
     *
     * @param name 实现名（如 "jndi"）
     * @return 解析器实例（未找到时可能为 null）
     */
    static DnsResolverProvider create(String name) {
        return ServiceProvider.of(DnsResolverProvider.class).getNewExtension(name);
    }

    /**
     * 注入解析配置（上游 DNS 服务器、超时等）。
     *
     * @param config DNS 配置
     * @return 当前实例（支持链式调用）
     */
    DnsResolverProvider config(DnsConfig config);

    /**
     * 解析指定名称与类型的记录。
     *
     * @param name 域名（如 "example.com"）
     * @param type 记录类型（A/AAAA/TXT/CNAME/MX/SRV/NS/SOA/PTR）
     * @return 记录列表（无记录时返回空列表；查询失败返回空列表）
     */
    List<DnsRecord> resolve(String name, String type);

    /**
     * 解析 A 记录（IPv4）。
     *
     * @param name 域名
     * @return IPv4 地址列表
     */
    default List<DnsRecord> resolveA(String name) {
        return resolve(name, "A");
    }

    /**
     * 解析 AAAA 记录（IPv6）。
     *
     * @param name 域名
     * @return IPv6 地址列表
     */
    default List<DnsRecord> resolveAAAA(String name) {
        return resolve(name, "AAAA");
    }

    /**
     * 解析 TXT 记录。
     *
     * @param name 域名
     * @return TXT 记录列表
     */
    default List<DnsRecord> resolveTxt(String name) {
        return resolve(name, "TXT");
    }

    /**
     * 解析 SRV 记录（服务发现常用，content 形如 "priority weight port target"）。
     *
     * @param name 服务名（如 "_scatter._tcp.example.com"）
     * @return SRV 记录列表
     */
    default List<DnsRecord> resolveSrv(String name) {
        return resolve(name, "SRV");
    }

    /**
     * 取第一条记录内容（便捷方法）。
     *
     * @param name 域名
     * @param type 记录类型
     * @return 首条记录内容；无记录返回 null
     */
    default String resolveFirst(String name, String type) {
        List<DnsRecord> records = resolve(name, type);
        return records.isEmpty() ? null : records.get(0).getContent();
    }
}
