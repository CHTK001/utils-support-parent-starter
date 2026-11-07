// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.starter.datasync.scheduler;

import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.DataSyncSource;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.starter.datasync.DataSyncServer;
import com.chua.starter.datasync.agent.AgentServerManager;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import com.chua.starter.datasync.model.DataSyncMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一源多目标的调度器行为测试。
 *
 * <p><b>为什么必须有</b>：多目标的真正定义是「源只读一次，数据同时写多张表」。
 * 只看代码无法证明这一点——很容易写成循环里每个目标各读一次源，
 * 语义看似正确但源被放大 N 倍（大表上直接把源库压满）。
 * 这里用计数器把「源读取次数」和「每个目标写入行数」锁死成断言。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class DefaultSyncDataSchedulerManagerMultiTargetTest {

    /**
     * 源只被读取一次，两个目标都收到全部数据。
     */
    @Test
    @DisplayName("一条映射两个目标：源读一次，两表各收全量")
    void fanOutWritesToAllSinks() {
        CountingServer server = new CountingServer();
        CountingSource source = new CountingSource("src-1", 3);
        CountingSink sinkA = new CountingSink("sink-a");
        CountingSink sinkB = new CountingSink("sink-b");
        server.registerSource(source);
        server.registerSink(sinkA);
        server.registerSink(sinkB);
        server.addMapping(mapping("m-1", "src-1", "sink-a", "sink-b"));

        server.schedulerManager().trigger("m-1");

        // 调度是异步的：等管线跑完再断言
        waitUntil(() -> sinkA.rows.get() >= 3, 5000);

        org.junit.jupiter.api.Assertions.assertEquals(1, source.readCount.get(),
                "源只应被读取一次，不能每个目标各读一次");
        org.junit.jupiter.api.Assertions.assertEquals(3, sinkA.rows.get(), "目标 A 应收到 3 行");
        org.junit.jupiter.api.Assertions.assertEquals(3, sinkB.rows.get(), "目标 B 应收到 3 行");
    }

    /**
     * 默认的 sinkIds() 仍然只有单个 sink（向后兼容）。
     */
    @Test
    @DisplayName("未覆盖 sinkIds() 时行为与单目标一致")
    void defaultsToSingleSink() {
        DataSyncMapping single = mapping("m-2", "src-1", "sink-a");
        org.junit.jupiter.api.Assertions.assertEquals(List.of("sink-a"), single.sinkIds(),
                "默认 sinkIds 应只含 sinkId()");
    }

    /**
     * 构造一个双目标映射。
     *
     * @param mappingId 映射标识
     * @param sourceId  源标识
     * @param sinkIds   目标标识
     * @return 映射
     */
    private static DataSyncMapping mapping(String mappingId, String sourceId, String... sinkIds) {
        return new DataSyncMapping() {
            @Override
            public String mappingId() {
                return mappingId;
            }

            @Override
            public String inputId() {
                return "input-1";
            }

            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String outputId() {
                return "output-1";
            }

            @Override
            public String sinkId() {
                return sinkIds.length == 0 ? null : sinkIds[0];
            }

            @Override
            public List<String> sinkIds() {
                return List.of(sinkIds);
            }

            @Override
            public com.chua.starter.datasync.config.DataSyncConfigDefinition config() {
                return null;
            }

            @Override
            public List<DataSyncFieldMapping> mappings() {
                return List.of();
            }

            @Override
            public int batch() {
                return 2;
            }

            @Override
            public String cronType() {
                return null;
            }

            @Override
            public String cron() {
                return null;
            }

            @Override
            public Map<String, Object> params() {
                return Map.of();
            }

            @Override
            public com.chua.common.support.task.scheduler.Trigger trigger() {
                return null;
            }
        };
    }

    /**
     * 轮询等待条件成立。
     *
     * @param condition 条件
     * @param timeoutMs 超时毫秒
     */
    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 可控的源：记录读取次数并返回固定行数。
     */
    private static final class CountingSource implements DataSyncAgentSource {
        private final String sourceId;
        private final int rows;
        private final AtomicInteger readCount = new AtomicInteger();

        private CountingSource(String sourceId, int rows) {
            this.sourceId = sourceId;
            this.rows = rows;
        }

        @Override
        public String sourceId() {
            return sourceId;
        }

        @Override
        public String inputId() {
            return "input-1";
        }

        @Override
        public Flux<Map<String, Object>> read(Map<String, Object> params) {
            readCount.incrementAndGet();
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 1; i <= rows; i++) {
                data.add(Map.of("id", i));
            }
            return Flux.fromIterable(data);
        }

        @Override
        public SyncDataOffset readOffset(Map<String, Object> params) {
            return null;
        }

        @Override
        public void close() {
        }
    }

    /**
     * 计数的目标。
     */
    private static final class CountingSink implements DataSyncAgentSink {
        private final String sinkId;
        private final AtomicInteger rows = new AtomicInteger();

        private CountingSink(String sinkId) {
            this.sinkId = sinkId;
        }

        @Override
        public String sinkId() {
            return sinkId;
        }

        @Override
        public void write(Flux<Map<String, Object>> data) {
            data.doOnNext(row -> rows.incrementAndGet()).blockLast();
        }

        @Override
        public void close() {
        }
    }

    /**
     * 不启动调度线程的最小服务端（避免后台轮询干扰用例）。
     */
    private static final class CountingServer extends com.chua.starter.datasync.DefaultDataSyncServer {
        private CountingServer() {
            super(new AgentServerManager() {
                @Override
                public void register(com.chua.datasync.agent.support.DataSyncAgent agent) {
                }

                @Override
                public void unregister(String agentId) {
                }

                @Override
                public com.chua.datasync.agent.support.DataSyncAgent getAgent(String agentId) {
                    return null;
                }

                @Override
                public List<com.chua.datasync.agent.support.DataSyncAgent> getAgents() {
                    return List.of();
                }

                @Override
                public void push(String agentId, String sinkId, List<Map<String, Object>> data) {
                }
            }, DefaultSyncDataSchedulerManager.SchedulerConfig.builder()
                    .directDispatch(true)
                    .build());
        }

        @Override
        public void start() {
            // 用例只调 trigger()，不需要后台 cron 轮询
        }
    }
}