package com.chua.datalake.support.cdc;

import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;

/**
 * MySQL CDC 入站适配器：binlog 事件 → 插入/更新/删除 → 数据envelope → pipelineengine。
 *
 * <p>通过 MySQL Binlog Protocol 实时捕获数据库变更，将行级事件转换为
 * {@link DataEnvelope} 交给 PipelineEngine 处理。</p>
 *
 * <p>前置条件：</p>
 * <ul>
 *   <li>MySQL 开启 binlog：{@code log-bin=mysql-bin}、{@code binlog-format=ROW}</li>
 *   <li>创建复制用户：{@code CREATE USER 'datalake'@'%' IDENTIFIED BY 'xxx'; GRANT REPLICATION SLAVE ON *.* TO 'datalake'@'%';}</li>
 * </ul>
 *
 * <p>生命周期：{@link #start()} 在守护线程内建立 binlog 流，最多等待 {@code connectTimeout} 毫秒；
 * 未建立连接即抛出 {@link IllegalStateException}，不会静默返回；{@link #isRunning()} 反映客户端真实连通状态；
 * {@link #stop()} 幂等，无论是否已经连上都释放客户端与线程。</p>
 *
 * <p>断点（至少一次）：用 {@code Builder.checkpoint(BiConsumer)} 接收每次事件成功进入管线后的
 * {@code (binlog 文件名, 位点)} 并自行持久化；重启用 {@code Builder.resumeFrom(file, position)} 续读。
 * 未接管线的投递失败不推进断点，重启后最坏是重复消费。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MysqlCdcListener {

    /**
     * Binlog 客户端
    */
    private volatile BinaryLogClient client;

    /**
     * MySQL 主机
    */
    private final String host;

    /**
     * MySQL 端口
    */
    private final int port;

    /**
     * 用户名
    */
    private final String username;

    /**
     * 密码
    */
    private final String password;

    /**
     * 服务器 标识（集群唯一）
    */
    private final int serverId;

    /**
     * 监听的表 标识（空 = 全部）
    */
    private final Long tableIdFilter;

    /**
     * 管线 标识
    */
    private final String pipelineId;

    /**
     * 管线引擎
    */
    private final PipelineEngine pipelineEngine;

    /**
     * 事件序号（含投递失败的事件），用于生成 traceId
    */
    private final AtomicLong eventCount = new AtomicLong(0);

    /**
     * 进入管线失败的事件数
    */
    private final LongAdder failureCount = new LongAdder();

    /**
     * 停止时等待监听线程退出的上限（毫秒）
    */
    private static final long TERMINATE_WAIT_MS = 3000L;

    /**
     * 已启动标记：stop() 之前一直为真，真实连通状态见 {@link #isRunning()}
    */
    private volatile boolean started = false;

    /**
     * 承载阻塞式 connect() 的线程
    */
    private volatile Thread streamThread;

    /**
     * 断点回调：每次事件成功进入管线后回传 (binlog 文件名, 位点)，由调用方决定如何持久化
    */
    private final BiConsumer<String, Long> checkpoint;

    /**
     * 恢复用的 binlog 文件名（null 表示从服务端当前位置开始）
    */
    private final String binlogFile;

    /**
     * 恢复用的 binlog 位点
    */
    private final long binlogPosition;

    /**
     * 建立连接的等待上限（毫秒）
    */
    private final long connectTimeoutMillis;

    /**
     * mysqlcdc监听器。
     * @param builder 构建器
     */
    private MysqlCdcListener(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.username = builder.username;
        this.password = builder.password;
        this.serverId = builder.serverId;
        this.tableIdFilter = builder.tableId;
        this.pipelineId = builder.pipelineId;
        this.pipelineEngine = builder.pipelineEngine;
        this.checkpoint = builder.checkpoint;
        this.binlogFile = builder.binlogFile;
        this.binlogPosition = builder.binlogPosition;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
    }

    /**
     * 创建构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 启动 CDC 监听。
     *
     * <p>连接在守护线程内建立，本方法最多等待 {@code connectTimeout} 毫秒：
     * 握手失败、通信异常或超时都会断开并抛出 {@link IllegalStateException}，
     * 不会在没跑起来的情况下正常返回。</p>
     *
     * @throws IllegalStateException 连接未建立时抛出
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        final BinaryLogClient newClient = new BinaryLogClient(host, port, username, password);
        newClient.setServerId(serverId);
        newClient.setConnectTimeout(connectTimeoutMillis);
        if (binlogFile != null && !binlogFile.isBlank()) {
            newClient.setBinlogFilename(binlogFile);
            newClient.setBinlogPosition(binlogPosition);
        }

        final CountDownLatch settled = new CountDownLatch(1);
        final Exception[] failure = new Exception[1];
        newClient.registerEventListener(event -> {
            try {
                handleEvent(event);
            } catch (Exception e) {
                log.error("[datalake-cdc] 事件处理失败: {}", e.getMessage(), e);
            }
        });
        newClient.registerLifecycleListener(new BinaryLogClient.LifecycleListener() {
            @Override
            public void onConnect(BinaryLogClient client) {
                settled.countDown();
            }

            @Override
            public void onCommunicationFailure(BinaryLogClient client, Exception ex) {
                failure[0] = ex;
                settled.countDown();
            }

            @Override
            public void onEventDeserializationFailure(BinaryLogClient client, Exception ex) {
                log.error("[datalake-cdc] 事件反序列化失败: {}", ex.getMessage(), ex);
            }

            @Override
            public void onDisconnect(BinaryLogClient client) {
                settled.countDown();
            }
        });

        Thread thread = new Thread(() -> {
            try {
                // connect() 阻塞直到 binlog 流结束，因此必须放在独立线程
                newClient.connect();
            } catch (Exception e) {
                failure[0] = e;
            } finally {
                settled.countDown();
                started = false;
            }
        }, "datalake-mysql-cdc");
        thread.setDaemon(true);

        client = newClient;
        streamThread = thread;
        started = true;
        thread.start();

        boolean signalled;
        try {
            signalled = settled.await(connectTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(newClient, thread);
            throw new IllegalStateException("CDC 启动等待被中断: " + host + ":" + port, e);
        }
        if (!signalled) {
            abort(newClient, thread);
            throw new IllegalStateException("CDC 连接超时 " + connectTimeoutMillis + "ms: " + host + ":" + port);
        }
        if (failure[0] != null || !newClient.isConnected()) {
            abort(newClient, thread);
            throw new IllegalStateException("CDC 启动失败: " + host + ":" + port
                    + (failure[0] == null ? " 未建立连接" : " " + failure[0].getMessage()), failure[0]);
        }
        log.info("[datalake-cdc] 启动成功: {}:{} (serverId={}, 起点={}:{})",
                host, port, serverId, newClient.getBinlogFilename(), newClient.getBinlogPosition());
    }

    /**
     * 断开连接并回收监听线程。
     *
     * <p>只要客户端存在就执行断开，不依赖连接是否已经建立。</p>
     */
    public synchronized void stop() {
        BinaryLogClient current = client;
        Thread thread = streamThread;
        if (current == null) {
            started = false;
            return;
        }
        try {
            current.disconnect();
            log.info("[datalake-cdc] 已停止, 共处理 {} 个事件", eventCount.get());
        } catch (Exception e) {
            log.warn("[datalake-cdc] 停止异常: {}", e.getMessage());
        } finally {
            started = false;
            client = null;
            streamThread = null;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(TERMINATE_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 启动失败时回收半连接的客户端与线程
     *
     * @param current 客户端
     * @param thread  监听线程
     */
    private static void abort(BinaryLogClient current, Thread thread) {
        try {
            current.disconnect();
        } catch (Exception e) {
            log.debug("[datalake-cdc] 启动失败后的断开异常: {}", e.getMessage());
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 处理 binlog 事件。
     *
     * @param event 事件
     */
    private void handleEvent(Event event) {
        EventData eventData = event.getData();
        if (eventData == null) {
            return;
        }

        if (eventData instanceof WriteRowsEventData) {
            WriteRowsEventData data = (WriteRowsEventData) eventData;
            if (tableIdFilter != null && data.getTableId() != tableIdFilter) {
                return;
            }

            for (Serializable[] row : data.getRows()) {
                Map<String, Object> rowMap = new HashMap<>();
                rowMap.put("_cdc_op", "INSERT");
                rowMap.put("_cdc_table_id", data.getTableId());
                rowMap.put("_cdc_ts", event.getHeader().getTimestamp());
                rowMap.put("row", row);
                publishEvent(rowMap);
            }
        } else if (eventData instanceof UpdateRowsEventData) {
            UpdateRowsEventData data = (UpdateRowsEventData) eventData;
            if (tableIdFilter != null && data.getTableId() != tableIdFilter) {
                return;
            }

            for (Map.Entry<Serializable[], Serializable[]> row : data.getRows()) {
                Map<String, Object> rowMap = new HashMap<>();
                rowMap.put("_cdc_op", "UPDATE");
                rowMap.put("_cdc_table_id", data.getTableId());
                rowMap.put("_cdc_ts", event.getHeader().getTimestamp());
                rowMap.put("before", row.getKey());
                rowMap.put("after", row.getValue());
                publishEvent(rowMap);
            }
        } else if (eventData instanceof DeleteRowsEventData) {
            DeleteRowsEventData data = (DeleteRowsEventData) eventData;
            if (tableIdFilter != null && data.getTableId() != tableIdFilter) {
                return;
            }

            for (Serializable[] row : data.getRows()) {
                Map<String, Object> rowMap = new HashMap<>();
                rowMap.put("_cdc_op", "DELETE");
                rowMap.put("_cdc_table_id", data.getTableId());
                rowMap.put("_cdc_ts", event.getHeader().getTimestamp());
                rowMap.put("row", row);
                publishEvent(rowMap);
            }
        }
    }

    /**
     * 发布事件到 pipelineengine，成功后回调断点。
     *
     * @param data 事件数据
     * @return true 表示已进入管线
     */
    private boolean publishEvent(Map<String, Object> data) {
        try {
            DataEnvelope envelope = DataEnvelope.builder()
                    .parsed(data)
                    .pipelineId(pipelineId)
                    .traceId("cdc-" + eventCount.incrementAndGet())
                    .timestamp(System.currentTimeMillis())
                    .build();
            envelope.addTrace("[CDC] op=" + data.get("_cdc_op"));

            pipelineEngine.execute(pipelineId, envelope);
            // 先投递成功再记断点：重启时最坏是重复消费，不会跳过未处理的事件
            if (checkpoint != null) {
                checkpoint.accept(currentBinlogFile(), currentBinlogPosition());
            }
            log.debug("[datalake-cdc] 事件处理完成: op={}, count={}", data.get("_cdc_op"), eventCount.get());
            return true;
        } catch (Exception e) {
            failureCount.increment();
            log.error("[datalake-cdc] 事件发布失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 返回已处理事件数。
     *
     * @return 事件计数
     */
    public long getEventCount() {
        return eventCount.get();
    }

    /**
     * 返回进入管线失败的事件数（这些事件不会推进断点）。
     *
     * @return 失败计数
     */
    public long getFailureCount() {
        return failureCount.sum();
    }

    /**
     * 是否运行中。
     *
     * @return true 表示 binlog 流已连通
     */
    public boolean isRunning() {
        BinaryLogClient current = client;
        return current != null && current.isConnected();
    }

    /**
     * 当前 binlog 文件名（断点用）。
     *
     * @return 文件名，未连接时返回 {@code null}
     */
    public String currentBinlogFile() {
        BinaryLogClient current = client;
        return current == null ? binlogFile : current.getBinlogFilename();
    }

    /**
     * 当前 binlog 位点（断点用）。
     *
     * @return 位点，未连接时返回构造时的配置值
     */
    public long currentBinlogPosition() {
        BinaryLogClient current = client;
        return current == null ? binlogPosition : current.getBinlogPosition();
    }

 // ━━━━━━━━━━━━━━ 构建器 ━━━━━━━━━━━━━━

    /**
     * MySQL CDC 监听器构建器。
     * @author CH
     * @since 4.0.0
     */
    public static class Builder {
        private String host = "127.0.0.1"; // 主机
        private int port = 3306; // 端口
        private String username = "root"; // 用户名
        private String password = ""; // 密码
        private int serverId = 1; // 服务端标识
        private Long tableId; // tableid
        private String pipelineId; // pipelineid
        private PipelineEngine pipelineEngine; // pipelineengine
        private String binlogFile; // 恢复起点：binlog 文件名
        private long binlogPosition; // 恢复起点：binlog 位点
        private long connectTimeoutMillis = 5000L; // 建立连接等待上限
        private BiConsumer<String, Long> checkpoint; // 断点回调

        /**
         * 恢复起点：binlog 文件名与位点，必须成对给出，否则从服务端当前位置开始。
         *
         * @param file binlog 文件名
         * @param position binlog 位点
         * @return 当前构建器
         */
        public Builder resumeFrom(String file, long position) {
            this.binlogFile = file;
            this.binlogPosition = position;
            return this;
        }

        /**
         * 建立连接的等待上限。
         *
         * @param connectTimeoutMillis 毫秒
         * @return 当前构建器
         */
        public Builder connectTimeout(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        /**
         * 事件成功进入管线后的断点回调。
         *
         * @param checkpoint 接收 (binlog 文件名, 位点) 的回调，可为 null
         * @return 当前构建器
         */
        public Builder checkpoint(BiConsumer<String, Long> checkpoint) {
            this.checkpoint = checkpoint;
            return this;
        }

        /**
         * 主机。
         * @param host 主机
         * @return 主机的结果
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        /**
         * 端口。
         * @param port 端口
         * @return 端口的结果
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        /**
         * 用户名。
         * @param username 用户名
         * @return 用户名的结果
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }
        /**
         * 密码。
         * @param password 密码
         * @return 密码的结果
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        /**
         * 服务端id。
         * @param serverId 服务端标识
         * @return 服务端id的结果
         */
        public Builder serverId(int serverId) {
            this.serverId = serverId;
            return this;
        }
        /**
         * tableid。
         * @param tableId tableid
         * @return tableId的结果
         */
        public Builder tableId(Long tableId) {
            this.tableId = tableId;
            return this;
        }
        /**
         * pipelineid。
         * @param pipelineId pipelineid
         * @return pipelineId的结果
         */
        public Builder pipelineId(String pipelineId) {
            this.pipelineId = pipelineId;
            return this;
        }
        /**
         * pipelineengine。
         * @param engine engine
         * @return pipelineEngine的结果
         */
        public Builder pipelineEngine(PipelineEngine engine) {
            this.pipelineEngine = engine;
            return this;
        }

        /**
         * 构建。
         * @return 构建的结果
         */
        public MysqlCdcListener build() {
            if (pipelineId == null || pipelineEngine == null) {
                throw new IllegalArgumentException("pipelineId 和 pipelineEngine 不能为空");
            }
            if (binlogFile != null && !binlogFile.isBlank() && binlogPosition <= 0) {
                throw new IllegalArgumentException("resumeFrom 给出 binlog 文件名时必须同时给出正数位点");
            }
            return new MysqlCdcListener(this);
        }
    }
}
