package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.DependencyEdge;

import java.util.List;
import java.util.Map;

/**
* APM 持久化存储 SPI 接口 — apmstorage。
*
* <p>由各 Handler 在 {@code addRecord / record / recordOpen} 后调用
* {@link #appendTransmission}、{@link #appendLeak} 等方法写入数据。
* 读取端（HTTP 查询接口 / 后续页面）通过 {@link #queryTransmissions} 等方法查询。</p>
*
* <p>设计原则：</p>
* <ul>
*   <li><b>append-only</b>：写入即落盘；同一份数据不重复存储</li>
*   <li><b>异步可选</b>：默认同步写入（保证持久性）；实现可按需异步</li>
*   <li><b>SPI 可插拔</b>：通过 {@link java.util.ServiceLoader} 发现实现，
*       无实现时使用 {@link NoopStorage}</li>
*   <li><b>后续兼容 Spring DataSource</b>：接口只暴露领域语义，不绑定 JDBC / SQLite
*       —— 由具体实现决定底层</li>
* </ul>
*
* <p>典型实现：</p>
* <ul>
*   <li>{@link NoopStorage}：默认无操作（保持内存模式兼容）</li>
*   <li>{@code InMemoryStorage}：内存 LRU + TTL，单进程无 DB 场景</li>
*   <li>{@code SqliteStorage}（独立模块）：嵌入式 SQLite，本地落盘</li>
*   <li>{@code JdbcStorage}（独立模块）：通过 {@link javax.sql.DataSource} 接入 MySQL/PG/...</li>
*   <li>{@code OtlpStorage}（独立模块）：OTLP HTTP/gRPC 导出到外部 Collector</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public interface ApmStorage {

    /** SPI 默认实现 键 — 当未配置 / SPI 找不到实现时，使用 noopstorage。 */
    String DEFAULT_NAME = "noop";

    /**
     * 启动存储 — 初始化连接池 / 打开文件 / 建表。
     * @param config 配置，不允许为 null
     */
    void start(StorageConfig config);

    /** 停止存储 — 关闭连接 / 刷盘。 */
    void stop();

    /**
     * 追加一条传输记录（扁平化）。
     * @param event 方法入参 event
     */
    void appendTransmission(TransmissionEvent event);

    /**
     * 追加一条依赖图边。
     * @param edge 方法入参 edge
     */
    void appendDependency(DependencyEdge edge);

    /**
     * 追加一条句柄泄漏记录。
     * @param record 记录，不允许为 null
     */
    void appendLeak(LeakRecord record);

    /**
     * 追加一条日志记录。
     * @param record 记录，不允许为 null
     */
    void appendLog(LogRecord record);

    /**
     * 查询传输记录。
     * @param query 查询，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    List<TransmissionEvent> queryTransmissions(Query query);

    /**
     * 查询依赖图边。
     * @param query 查询，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    List<DependencyEdge> queryDependencies(Query query);

    /**
     * 查询句柄泄漏。
     * @param query 查询，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    List<LeakRecord> queryLeaks(Query query);

    /**
     * 查询日志。
     * @param query 查询，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    List<LogRecord> queryLogs(Query query);

    /**
     * 统计 — 各类型当前总数 / 命中 查询 的数量。
     * @return 结果映射，无数据时为空映射
     */
    Map<String, Long> stats();

    /**
     * 清理过期数据 — 由实现决定触发时机（定时 / 容量超限）。
     * @param retentionMillis retention毫秒数，不允许为 null
     * @return 结果数值
     */
    long cleanup(long retentionMillis);

    /**
     * 实现名称 — 用于日志区分 / 多实现选择。
     * @return 结果字符串
     */
    String name();
}