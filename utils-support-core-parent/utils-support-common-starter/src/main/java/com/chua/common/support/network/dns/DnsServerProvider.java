package com.chua.common.support.network.dns;

import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
 * 内嵌 DNS 服务端 SPI。
 *
 * <p>在 JVM 内启动一个 DNS 服务器（默认 UDP 53，可配端口），维护内存 zone 记录，
 * 支持 A/AAAA/TXT/CNAME/MX/NS/SOA/SRV 的查询应答。适用于：</p>
 * <ul>
 *   <li>测试环境模拟 DNS（无需真实域名/网络）；</li>
 *   <li>内网服务发现（配合 {@link com.chua.common.support.scatter.Scatter} 的 DNS 发现模式）；</li>
 *   <li>本地解析劫持 / 灰度切流实验。</li>
 * </ul>
 *
 * <p>通过 SPI 路由实现：{@code simple}（内置轻量实现，UDP，内存 zone）。
 * 记录模型复用 {@link DnsRecord}；SRV 记录 content 形如
 * {@code "priority weight port target"}（与 {@link DnsResolverProvider#resolveSrv} 对齐）。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (DnsServerProvider server = DnsServerProvider.create("simple")
 *         .config(DnsConfig.builder().zoneName("test.local").build())) {
 *     server.addRecord(DnsRecord.builder().name("app.test.local").type("A")
 *             .content("127.0.0.1").ttl(30).build());
 *     server.start();
 *     System.out.println("dns server port=" + server.getPort());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface DnsServerProvider extends AutoCloseable {

    /**
     * 内置轻量实现名
     */
    String SIMPLE = "simple";

    /**
     * 通过 SPI 创建指定名称的 DNS 服务端实例。
     *
     * @param name 实现名（如 "simple"）
     * @return 服务端实例（未找到时可能为 null）
     */
    static DnsServerProvider create(String name) {
        return ServiceProvider.of(DnsServerProvider.class).getNewExtension(name);
    }

    /**
     * 注入服务端配置（监听端口、zone 名等）。
     *
     * @param config DNS 配置
     * @return 当前实例（支持链式调用）
     */
    DnsServerProvider config(DnsConfig config);

    /**
     * 添加一条记录（RRset 语义：name + type + content 相同视为同一条，重复添加幂等；
     * 同名同型不同 content 的记录共存——scatter 多节点共用同一 SRV 名依赖此行为）。
     *
     * @param record DNS 记录
     * @return 当前实例（支持链式调用）
     */
    DnsServerProvider addRecord(DnsRecord record);

    /**
     * 删除一条记录（name + type 匹配，删除该名下该类型的全部记录）。
     *
     * @param name 记录名
     * @param type 记录类型
     * @return 当前实例（支持链式调用）
     */
    DnsServerProvider removeRecord(String name, String type);

    /**
     * 删除一条记录（name + type + content 精确匹配；content 为空时退化为按 name + type 删除）。
     *
     * @param record DNS 记录
     * @return 当前实例（支持链式调用）
     */
    default DnsServerProvider removeRecord(DnsRecord record) {
        return record == null ? this : removeRecord(record.getName(), record.getType());
    }

    /**
     * 列出全部内存记录。
     *
     * @return 记录列表
     */
    List<DnsRecord> listRecords();

    /**
     * 启动服务端（绑定端口并开始应答）。
     *
     * @throws Exception 绑定失败（端口被占用等）
     */
    void start() throws Exception;

    /**
     * 停止服务端并释放端口。
     */
    void stop();

    /**
     * 实际绑定的端口（启动后有效；未启动返回 -1）。
     *
     * @return 绑定端口
     */
    int getPort();

    /**
     * 设置监听端口（标准 DNS 为 53；0 表示由系统分配，仅建议测试使用）。
     *
     * @param port 监听端口
     * @return 当前实例（支持链式调用）
     */
    default DnsServerProvider port(int port) {
        return this;
    }

    /**
     * 设置监听地址（默认 0.0.0.0）。
     *
     * @param host 监听地址
     * @return 当前实例（支持链式调用）
     */
    default DnsServerProvider bindHost(String host) {
        return this;
    }

    /**
     * 便捷关闭：等价 {@link #stop()}。
     */
    @Override
    default void close() {
        stop();
    }
}
