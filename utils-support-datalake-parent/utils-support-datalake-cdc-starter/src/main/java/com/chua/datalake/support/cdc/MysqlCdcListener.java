package com.chua.datalake.support.cdc;

import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.spi.pipeline.PipelineEngine;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MysqlCdcListener {

    /** Binlog 客户端 */
    private volatile BinaryLogClient client;

    /** MySQL 主机 */
    private final String host;

    /** MySQL 端口 */
    private final int port;

    /** 用户名 */
    private final String username;

    /** 密码 */
    private final String password;

    /** 服务器 标识（集群唯一） */
    private final int serverId;

    /** 监听的表 标识（空 = 全部） */
    private final Long tableIdFilter;

    /** 管线 标识 */
    private final String pipelineId;

    /** 管线引擎 */
    private final PipelineEngine pipelineEngine;

    /** 事件计数器 */
    private final AtomicLong eventCount = new AtomicLong(0);

    /** 运行状态 */
    private volatile boolean running = false;

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
     */
    public void start() {
        if (running) {
            return;
        }
        try {
            client = new BinaryLogClient(host, port, username, password);
            client.setServerId(serverId);

            client.registerEventListener(event -> {
                try {
                    handleEvent(event);
                } catch (Exception e) {
                    log.error("[datalake-cdc] 事件处理失败: {}", e.getMessage(), e);
                }
            });

            client.connect();
            running = true;
            log.info("[datalake-cdc] 启动成功: {}:{}/ (serverId={})", host, port, serverId);
        } catch (Exception e) {
            log.error("[datalake-cdc] 启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止 CDC 监听。
     */
    public void stop() {
        if (!running || client == null) {
            return;
        }
        try {
            client.disconnect();
            running = false;
            log.info("[datalake-cdc] 已停止, 共处理 {} 个事件", eventCount.get());
        } catch (Exception e) {
            log.warn("[datalake-cdc] 停止异常: {}", e.getMessage());
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
     * 发布事件到 pipelineengine。
     *
     * @param data 事件数据
     */
    private void publishEvent(Map<String, Object> data) {
        try {
            DataEnvelope envelope = DataEnvelope.builder()
                    .parsed(data)
                    .pipelineId(pipelineId)
                    .traceId("cdc-" + eventCount.incrementAndGet())
                    .timestamp(System.currentTimeMillis())
                    .build();
            envelope.addTrace("[CDC] op=" + data.get("_cdc_op"));

            pipelineEngine.execute(pipelineId, envelope);
            log.debug("[datalake-cdc] 事件处理完成: op={}, count={}", data.get("_cdc_op"), eventCount.get());
        } catch (Exception e) {
            log.error("[datalake-cdc] 事件发布失败: {}", e.getMessage(), e);
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
     * 是否运行中。
     *
     * @return true 表示已启动
     */
    public boolean isRunning() {
        return running;
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
            return new MysqlCdcListener(this);
        }
    }
}
