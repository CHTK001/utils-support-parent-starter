package com.chua.common.support.network.dns;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
* DNS 记录模型。
*
* <p>描述一条 DNS 记录，包含名称、类型、内容、TTL 等核心字段，
* 用于 {@link DnsProvider} 的添加、删除与查询操作。</p>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
@Data
@Builder
@Accessors(chain = true)
public class DnsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
    * 记录名称（完整域名，如 _acme-challenge.example.com）
     */
    private String name;

    /**
    * 记录类型（A、AAAA、TXT、CNAME、MX 等）
     */
    private String type;

    /**
    * 记录内容（A 记录为 IP，TXT 记录为文本值）
     */
    private String content;

    /**
    * TTL（秒），默认 120
     */
    @Builder.Default
    private int ttl = 120;

    /**
    * 是否代理（Cloudflare 特有，A/AAAA/CNAME 记录可开启）
     */
    @Builder.Default
    private boolean proxied = false;

    /**
    * 记录优先级（MX 等类型使用）
     */
    private Integer priority;

    /**
    * 服务商内部记录 ID（查询结果回填，删除时使用）
     */
    private String id;
}
