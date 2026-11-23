package com.chua.common.support.network.dns;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 JDK JNDI（{@code com.sun.jndi.dns.DnsContextFactory}）的 DNS 解析器。
 *
 * <p>零第三方依赖；支持 A/AAAA/TXT/CNAME/MX/SRV/NS/SOA/PTR。
 * 上游 DNS 服务器通过 {@link DnsConfig#getBaseUrl()} 指定
 * （如 {@code dns://223.5.5.5} 或 {@code dns://127.0.0.1:5353}），缺省使用系统解析链路。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi("jndi")
public class JndiDnsResolver implements DnsResolverProvider {

    /**
     * JNDI DNS 工厂
     */
    private static final String DNS_CONTEXT_FACTORY = "com.sun.jndi.dns.DnsContextFactory";

    /**
     * 已支持的记录类型集合（用于快速校验）
     */
    private static final java.util.Set<String> SUPPORTED_TYPES = new java.util.HashSet<>(java.util.Arrays.asList(
            "A", "AAAA", "TXT", "CNAME", "MX", "SRV", "NS", "SOA", "PTR"));

    private volatile DnsConfig config = DnsConfig.builder().build();
    private final ConcurrentMap<String, DirContext> contextCache = new ConcurrentHashMap<>();

    @Override
    public DnsResolverProvider config(DnsConfig config) {
        if (config != null) {
            this.config = config;
            contextCache.clear();
        }
        return this;
    }

    @Override
    public List<DnsRecord> resolve(String name, String type) {
        if (name == null || name.trim().isEmpty() || type == null || type.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String recordType = type.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(recordType)) {
            log.debug("不支持的 DNS 记录类型: {}", recordType);
            return Collections.emptyList();
        }
        String queryName = normalizeName(name);
        try {
            DirContext context = context();
            Attributes attributes = context.getAttributes(queryName, new String[]{recordType});
            javax.naming.directory.Attribute attribute = attributes.get(recordType);
            if (attribute == null || attribute.size() == 0) {
                return Collections.emptyList();
            }
            List<DnsRecord> records = new java.util.ArrayList<>(attribute.size());
            NamingEnumeration<?> values = attribute.getAll();
            while (values.hasMore()) {
                Object value = values.next();
                if (value != null) {
                    records.add(DnsRecord.builder()
                            .name(queryName)
                            .type(recordType)
                            .content(normalizeContent(String.valueOf(value)))
                            .ttl((int) Math.max(1, config.getTimeoutMs() / 1000L))
                            .build());
                }
            }
            return records;
        } catch (NamingException e) {
            // NameNotFoundException 等统一按「无记录」处理，不打断调用方
            log.debug("DNS 解析无结果: {} {} - {}", queryName, recordType, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("DNS 解析失败: {} {} - {}", queryName, recordType, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 获取（或创建）当前配置对应的 JNDI DNS 上下文。
     *
     * @return 目录上下文
     * @throws NamingException 上下文创建失败
     */
    private DirContext context() throws NamingException {
        String url = config.getBaseUrl();
        String key = url == null ? "" : url.trim();
        DirContext cached = contextCache.get(key);
        if (cached != null) {
            return cached;
        }
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, DNS_CONTEXT_FACTORY);
        if (!key.isEmpty()) {
            env.put(Context.PROVIDER_URL, key.startsWith("dns://") ? key : "dns://" + key);
        }
        // 请求级超时（毫秒）：JNDI DNS 提供方属性
        long timeout = Math.max(100L, config.getTimeoutMs());
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(Math.min(timeout, 5_000L)));
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = new InitialDirContext(env);
        DirContext previous = contextCache.putIfAbsent(key, context);
        return previous == null ? context : previous;
    }

    /**
     * 归一化查询名：去空白、去协议前缀、去末尾点。
     *
     * @param name 原始名称
     * @return 归一化名称
     */
    private static String normalizeName(String name) {
        String text = name.trim();
        if (text.startsWith("dns:")) {
            text = text.substring(text.indexOf("://") + 3);
        }
        while (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 归一化记录内容：去末尾点（JNDI 对域名类值常带绝对域后缀）。
     *
     * @param content 原始内容
     * @return 归一化内容
     */
    private static String normalizeContent(String content) {
        String text = content.trim();
        if (text.endsWith(".") && !text.endsWith("\\.")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
