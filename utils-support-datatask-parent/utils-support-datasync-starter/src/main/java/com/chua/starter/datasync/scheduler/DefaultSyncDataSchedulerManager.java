package com.chua.starter.datasync.scheduler;

import com.chua.common.support.task.scheduler.CronTrigger;
import com.chua.common.support.task.scheduler.Trigger;
import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.executor.ReactorDataSyncExecutor;
import com.chua.starter.datasync.DataSyncServer;
import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import com.chua.starter.datasync.mapping.DefaultFieldMappingConverter;
import com.chua.starter.datasync.mapping.FieldMappingConverter;
import com.chua.starter.datasync.model.DataSyncMapping;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 默认数据同步调度器管理器。
 *
 * <p>每秒轮询一次，检查 Cron 条件并执行字段映射转换。
 * 执行器由 ExecutorManager 池化管理，调度器仅负责发布数据。</p>
 *
 * <p>管线默认使用 {@link Schedulers#boundedElastic()} 异步执行，
 * 避免调度线程被慢 Source 阻塞；支持可配置的背压、重试与并发参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DefaultSyncDataSchedulerManager implements SyncDataSchedulerManager {

    /**
     * 调度间隔（秒）
     */
    private static final int SCHEDULER_INTERVAL_SECONDS = 1;

    /**
     * Sink 键分隔符
     */
    private static final String SINK_KEY_SEPARATOR = "|";

    /**
     * 默认批次大小
     */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * 调度线程名前缀
     */
    private static final String SCHEDULER_THREAD_NAME_PREFIX = "datasync-scheduler-";

    /**
     * 调度器配置
     */
    private final SchedulerConfig config;

    /**
     * 数据同步服务器
     */
    private final DataSyncServer dataSyncServer;

    /**
     * 调度线程池
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 字段映射转换器
     */
    private final FieldMappingConverter fieldMappingConverter;

    /**
     * 已订阅的 Sink 标识集合（格式：outputId|sinkId），避免重复订阅
     */
    private final Set<String> subscribedSinkKeys;

    /**
     * 触发器缓存（mappingId -> Trigger），避免每秒重复解析 Cron 表达式
     */
    private final Map<String, Trigger> triggerCache = new ConcurrentHashMap<>();

    /**
     * 失败重试计数器（mappingId -> 连续失败次数），用于熔断退避
     */
    private final Map<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    /**
     * 活跃的订阅引用，防止 Depose 被 GC 回收导致管线取消。
     */
    private final java.util.List<reactor.core.Disposable> activeSubscriptions = new CopyOnWriteArrayList<>();

    /**
     * 调度器配置。
     */
    @Data
    @Builder
    public static class SchedulerConfig {

        /**
         * flatMap 并行度
         */
        /** flatMap 并行度 */
        @Builder.Default
        /** Flat映射parallelism */
        private int flatMapParallelism = 10;

        /**
         * 最大 buffer 行数上限（默认无限制，设置 >0 的数值后启用上限）
         */
        /** 最大缓冲行数 */
        @Builder.Default
        /** 最大值缓冲区rows */
        private long maxBufferRows = Long.MAX_VALUE;

        /**
         * 每秒最大请求数（默认 0 表示无限制，>0 时启用 {@link Flux#limitRate(int)}）
         */
        /** 每秒最大速率 */
        @Builder.Default
        /** 最大值比率PERsecond */
        private int maxRatePerSecond = 0;

        /**
         * 最大重试次数
         */
        /** 最大重试次数 */
        @Builder.Default
        /** 重试最大值attempts */
        private int retryMaxAttempts = 2;

        /**
         * 重试初始退避毫秒
         */
        /** 重试退避时间（毫秒） */
        @Builder.Default
        /** 重试backoffMS */
        private long retryBackoffMs = 500;

        /**
         * 熔断阈值：连续失败超过此次数后停止重试
         */
        /** 熔断阈值 */
        @Builder.Default
        /** Circuitbreaker阈值 */
        private int circuitBreakerThreshold = 10;

        /**
         * 内存安全模式：根据 JVM 最大堆自动计算管线中最大在飞行数，防止 OOM。
         * 计算公式：{@code maxInFlightRows = (maxMemory * memoryPercent / 100) / estimatedRowBytes}
         */
        /** 是否启用内存安全 */
        @Builder.Default
        /** Memorysafe是否启用 */
        private boolean memorySafeEnabled = true;

        /**
         * 用于内存安全计算的堆内存百分比（默认 30%）
         */
        /** 内存占用百分比 */
        @Builder.Default
        /** Memory百分比 */
        private int memoryPercent = 30;

        /**
         * 估算单行数据字节数（Map 开销 + 字段值，默认 256 字节）
         */
        /** 预估行字节数 */
        @Builder.Default
        /** Estimated行bytes */
        private int estimatedRowBytes = 256;

        /**
         * 是否启用直连派发模式（同 JVM 内 publish 直接调用 subscriber，绕过 Chronicle）。
         * 默认 false 使用 Chronicle 磁盘派发；true 时跳过 Chronicle 提高吞吐。
         */
        /** 是否直接分发 */
        @Builder.Default
        /** Directdispatch */
        private boolean directDispatch = false;

        /**
         * 计算最大在飞行数（受 {@link #memorySafeEnabled} 控制）。
         *
         * @param batchSize 当前批次大小
         * @return publishOn prefetch 值
         */
        public int computePrefetch(int batchSize) {
            if (!memorySafeEnabled) {
                return 256;
            }
            long maxMem = Runtime.getRuntime().maxMemory();
            long available = maxMem * memoryPercent / 100;
            long maxRows = available / Math.max(estimatedRowBytes, 1);
            int prefetch = (int) (maxRows / Math.max(batchSize, 1));
            return Math.max(1, Math.min(prefetch, 10000));
        }
    }

    public DefaultSyncDataSchedulerManager(DataSyncServer dataSyncServer) {
        this(dataSyncServer, SchedulerConfig.builder().build());
    }

    public DefaultSyncDataSchedulerManager(DataSyncServer dataSyncServer, SchedulerConfig config) {
        this.dataSyncServer = dataSyncServer;
        this.config = config;
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, SCHEDULER_THREAD_NAME_PREFIX + r.hashCode());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.fieldMappingConverter = new DefaultFieldMappingConverter();
        this.subscribedSinkKeys = ConcurrentHashMap.newKeySet();
    }

    @Override
    public void addMapping(DataSyncMapping mapping) {
        dataSyncServer.mappingManager().addMapping(mapping);
    }

    @Override
    public void removeMapping(String mappingId) {
        dataSyncServer.mappingManager().removeMapping(mappingId);
        triggerCache.remove(mappingId);
    }

    @Override
    public DataSyncMapping getMapping(String mappingId) {
        return dataSyncServer.mappingManager().getMappings().stream()
                .filter(m -> m.mappingId().equals(mappingId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<DataSyncMapping> getMappings() {
        return dataSyncServer.mappingManager().getMappings();
    }

    @Override
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::executePendingMappings,
                SCHEDULER_INTERVAL_SECONDS,
                SCHEDULER_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        log.info("SyncDataSchedulerManager 启动成功，调度间隔: {} 秒", SCHEDULER_INTERVAL_SECONDS);
    }

    @Override
    public void stop() {
        scheduler.shutdown();
        subscribedSinkKeys.clear();
        log.info("SyncDataSchedulerManager 已停止");
    }

    @Override
    public void trigger(String mappingId) {
        if (mappingId == null || mappingId.isBlank()) {
            log.error("手动触发失败：mappingId 为空");
            return;
        }
        DataSyncMapping mapping = getMapping(mappingId);
        if (mapping == null) {
            log.warn("手动触发失败：映射不存在 mappingId={}", mappingId);
            return;
        }
        log.info("手动触发映射: mappingId={}", mappingId);
        executeMapping(mapping);
    }

    /**
     * 执行待处理的映射。
     */
    private void executePendingMappings() {
        try {
            List<DataSyncMapping> mappings = dataSyncServer.mappingManager().getMappings();
            for (DataSyncMapping mapping : mappings) {
                if (!isTriggerSatisfied(mapping)) {
                    continue;
                }
                executeMapping(mapping);
            }
        } catch (Exception e) {
            log.error("执行定时任务异常", e);
        }
    }

    /**
     * 检查触发器条件是否满足。
     *
     * <p>优先使用 {@link DataSyncMapping#trigger()}；若为空则退化为使用 {@code cron()} 字符串构造
     * {@link CronTrigger} 来判断；若 cron 为空或无效，则使用默认的 {@code SimpleTrigger}（固定间隔 1 分钟）。</p>
     *
     * <p>已将 cron 解析结果缓存至 {@link #triggerCache}，避免每秒重复构造 Trigger 实例。</p>
     *
     * @param mapping 映射配置
     * @return true 表示满足触发条件
     */
    private boolean isTriggerSatisfied(DataSyncMapping mapping) {
        Trigger trigger = mapping.trigger();
        if (trigger == null) {
            // 从缓存获取或创建 Trigger
            trigger = triggerCache.computeIfAbsent(mapping.mappingId(), id -> {
                String cron = mapping.cron();
                if (cron == null || cron.isBlank()) {
                    // cron 为空，使用默认 SimpleTrigger（每分钟触发一次）
                    return new com.chua.common.support.task.scheduler.SimpleTrigger(java.time.Duration.ofMinutes(1));
                } else {
                    try {
                        return new CronTrigger(cron);
                    } catch (Exception e) {
                        log.warn("Cron 表达式构造失败: mappingId={}, cron={}，使用默认 SimpleTrigger", mapping.mappingId(), cron, e);
                        return new com.chua.common.support.task.scheduler.SimpleTrigger(java.time.Duration.ofMinutes(1));
                    }
                }
            });
        }

        // 使用 Trigger 计算从前一秒开始的下一次触发时间
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime checkFrom = now.minusSeconds(1);
        try {
            java.time.LocalDateTime nextFire = trigger.nextExecutionTime(checkFrom);
            return nextFire != null && !nextFire.isAfter(now);
        } catch (Exception e) {
            log.warn("触发器执行异常: mappingId={}", mapping.mappingId(), e);
            return false;
        }
    }

    /**
     * 执行单个映射。
     *
     * @param mapping 映射配置
     */
    private void executeMapping(DataSyncMapping mapping) {
        try {
            // 基础校验
            if (mapping.mappingId() == null || mapping.mappingId().isBlank()) {
                log.error("映射ID为空，跳过执行");
                return;
            }
            String sourceId = mapping.sourceId();
            if (sourceId == null || sourceId.isBlank()) {
                log.warn("映射缺少 sourceId: mappingId={}", mapping.mappingId());
                return;
            }
            DataSyncAgentSource source = dataSyncServer.getSource(sourceId);
            if (source == null) {
                log.warn("找不到Source: mappingId={}, sourceId={}", mapping.mappingId(), sourceId);
                return;
            }

            String sinkId = mapping.sinkId();
            if (sinkId == null || sinkId.isBlank()) {
                log.warn("映射缺少 sinkId: mappingId={}", mapping.mappingId());
                return;
            }
            DataSyncAgentSink sink = dataSyncServer.getSink(sinkId);
            if (sink == null) {
                log.warn("找不到Sink: mappingId={}, sinkId={}", mapping.mappingId(), sinkId);
                return;
            }

        // 方向约束：source 应为 INPUT，sink 应为 OUTPUT
        if (source instanceof com.chua.datasync.agent.support.model.Directional) {
            com.chua.datasync.agent.support.model.Direction srcDir = ((com.chua.datasync.agent.support.model.Directional) source).direction();
            if (srcDir != com.chua.datasync.agent.support.model.Direction.INPUT) {
                log.error("Source 方向错误，应为 INPUT，已阻止执行: mappingId={}, sourceId={}, direction={}", mapping.mappingId(), sourceId, srcDir);
                return;
            }
        }
        if (sink instanceof com.chua.datasync.agent.support.model.Directional) {
            com.chua.datasync.agent.support.model.Direction sinkDir = ((com.chua.datasync.agent.support.model.Directional) sink).direction();
            if (sinkDir != com.chua.datasync.agent.support.model.Direction.OUTPUT) {
                log.error("Sink 方向错误，应为 OUTPUT，已阻止执行: mappingId={}, sinkId={}, direction={}", mapping.mappingId(), sinkId, sinkDir);
                return;
            }
        }

        log.debug("执行映射: mappingId={}, sourceId={}, sinkId={}", mapping.mappingId(), sourceId, sinkId);

            String outputId = mapping.outputId();
            ReactorDataSyncExecutor executor = dataSyncServer.executorManager().getExecutor(outputId);
            String sinkKey = outputId + SINK_KEY_SEPARATOR + sinkId;
            if (subscribedSinkKeys.add(sinkKey)) {
                executor.subscribe(outputId, data -> sink.write(Flux.fromIterable(data)));
            }

        List<DataSyncFieldMapping> fieldMappings = mapping.mappings() == null ? List.of() : mapping.mappings();
        Map<String, Object> readParams = buildReadParams(mapping, source);

        // 构建响应式管线：IO 读（弹性线程池）→ buffer → CPU 处理（parallel 固定线程池）
        Flux<Map<String, Object>> sourceFlux = Flux.just(source)
                .flatMap(s -> s.read(readParams))
                .subscribeOn(Schedulers.boundedElastic());

        // 背压控制
        if (config.getMaxRatePerSecond() > 0) {
            sourceFlux = sourceFlux.limitRate(config.getMaxRatePerSecond());
        }

        // 分批处理（CPU 密集型：字段转换 + 发布，切到 parallel 固定线程池减少切换开销）
        int batchSize = mapping.batch() > 0 ? mapping.batch() : DEFAULT_BATCH_SIZE;
        long maxRows = config.getMaxBufferRows() > 0 ? config.getMaxBufferRows() : Long.MAX_VALUE;
        int prefetch = config.computePrefetch(batchSize);
        Flux<List<Map<String, Object>>> batched = sourceFlux
                .buffer(batchSize)
                .take(maxRows / Math.max(1, batchSize))
                .publishOn(Schedulers.parallel(), prefetch);

        // 重试 + 转换 + 发布
        reactor.core.Disposable disposable = batched
                .flatMap(batchData -> {
                    List<Map<String, Object>> transformedBatch = applyFieldMappings(batchData, fieldMappings);
                    try {
                        executor.publish(outputId, transformedBatch);
                        log.trace("已发布批次: mappingId={}, outputId={}, batchSize={}",
                                mapping.mappingId(), outputId, transformedBatch.size());
                    } catch (Exception publishEx) {
                        log.error("发布批次异常: mappingId={}, outputId={}, batchSize={}, error={}",
                                mapping.mappingId(), outputId, transformedBatch.size(), publishEx.getMessage(), publishEx);
                    }

                    if (!transformedBatch.isEmpty()) {
                        Object lastOffset = transformedBatch.get(transformedBatch.size() - 1).getOrDefault("id",
                                transformedBatch.get(transformedBatch.size() - 1).values().stream().findFirst().orElse(null));
                        persistOffset(source, mapping, lastOffset);
                    }
                    return Flux.empty();
                }, config.getFlatMapParallelism())
                .retryWhen(Retry.backoff(config.getRetryMaxAttempts(), Duration.ofMillis(config.getRetryBackoffMs()))
                        .filter(throwable -> {
                            AtomicInteger counter = retryCounters.computeIfAbsent(
                                    mapping.mappingId(), k -> new AtomicInteger(0));
                            int failures = counter.incrementAndGet();
                            if (failures > config.getCircuitBreakerThreshold()) {
                                log.error("熔断触发: mappingId={}, 连续失败次数={}", mapping.mappingId(), failures);
                                return false;
                            }
                            return true;
                        })
                        .doAfterRetry(rs -> log.warn("重试执行: mappingId={}, 次数={}",
                                mapping.mappingId(), rs.totalRetries() + 1)))
                .doOnComplete(() -> {
                    AtomicInteger counter = retryCounters.get(mapping.mappingId());
                    if (counter != null) {
                        counter.set(0);
                    }
                })
                .subscribe(
                        null,
                        error -> log.error("映射执行异常: mappingId={}", mapping.mappingId(), error),
                        () -> log.debug("映射执行完成: mappingId={}", mapping.mappingId())
                );
        // 持有 Disposable 引用，防止被 GC 回收导致管线取消
        activeSubscriptions.add(disposable);
        } catch (Exception e) {
            log.error("执行映射失败: mappingId={}", mapping.mappingId(), e);
        }
    }

    /**
     * 应用字段映射。
     *
     * @param batchData 数据批次
     * @param mappings 字段映射列表
     * @return 转换后的数据列表
     */
    private List<Map<String, Object>> applyFieldMappings(List<Map<String, Object>> batchData, List<DataSyncFieldMapping> mappings) {
        if (mappings.isEmpty()) {
            return batchData;
        }
        return batchData.stream()
                .map(record -> fieldMappingConverter.applyMappings(record, mappings))
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildReadParams(DataSyncMapping mapping, DataSyncAgentSource source) {
        Map<String, Object> params = mapping.params() == null ? new HashMap<>() : new HashMap<>(mapping.params());
        com.chua.datasync.agent.support.model.SyncDataOffset storedOffset = source.readOffset(params);
        if (storedOffset != null) {
            params.put("offset", storedOffset.offsetValue());
        }
        return params;
    }

    /**
     * 持久化 Source 的读取偏移量。
     *
     * <p>由 publish 成功后调用，确保至少已成功发送至执行器后再推进 offset，
     * 避免进程重启后丢失已投递的数据。</p>
     *
     * @param source 数据源
     * @param mapping 映射配置
     * @param lastOffset 本次最后一条数据的偏移量
     */
    private void persistOffset(DataSyncAgentSource source, DataSyncMapping mapping, Object lastOffset) {
        if (lastOffset == null) {
            return;
        }
        try {
            com.chua.datasync.agent.support.model.SyncDataOffset offset = new com.chua.datasync.agent.support.model.SyncDataOffset(
                    source.sourceId(),
                    lastOffset,
                    System.currentTimeMillis(),
                    mapping.mappingId()
            );
            source.writeOffset(offset);
        } catch (Exception e) {
            log.warn("持久化 offset 失败: mappingId={}, sourceId={}", mapping.mappingId(), source.sourceId(), e);
        }
    }
}