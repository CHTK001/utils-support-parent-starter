package com.chua.spider.support.config;

import com.chua.spider.support.config.model.SpiderDefinition;
import com.chua.spider.support.config.store.SpiderDefinitionStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 爬虫定时调度服务。
 *
 * <p>根据 {@link SpiderDefinition#getSpiderScheduleEnable()} 与
 * {@link SpiderDefinition#getSpiderScheduleCron()}，为每个启用定时的爬虫注册调度任务。
 * 下一次触发时间用 {@link CronExpression#nextAfter} 计算。</p>
 *
 * <p>每分钟扫描一次所有启用定时的爬虫，根据 cron 计算下次触发时间并重排调度。
 * 任务回调由消费者决定如何启动爬虫（通常由编排层注入具体执行逻辑）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SpiderTimerService {

    /**
     * 默认调度池大小。
     */
    private static final int DEFAULT_POOL_SIZE = 4;

    /**
     * 扫描间隔（秒）：每分钟扫描一次所有定时任务。
     */
    private static final int SCAN_INTERVAL_SECONDS = 60;

    /**
     * 调度线程池
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 爬虫定义存储
     */
    private final SpiderDefinitionStore definitionStore;

    /**
     * 任务回调：接收到时执行爬虫逻辑
     */
    private Consumer<SpiderDefinition> taskHandler = def -> {
 // 默认空实现，由调用方通过 设置任务处理器 注入
        log.info("[spider-config] 到期: spiderCode={}", def.getSpiderCode());
    };

    /**
      * 已注册的调度任务：蜘蛛编码 -> 调度期货
     */
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    /**
      * 扫描线程的 期货（用于 关闭 时取消）
     */
    private ScheduledFuture<?> scanFuture;

    /**
      * 创建 蜘蛛定时器服务 实例
     * @param definitionStore definition存储
     */
    public SpiderTimerService(SpiderDefinitionStore definitionStore) {
        this.scheduler = Executors.newScheduledThreadPool(DEFAULT_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r, "spider-timer");
                    t.setDaemon(true);
                    return t;
                });
        this.definitionStore = definitionStore;
    }

    /**
     * 设置任务处理器。
     *
     * @param handler 任务回调
     */
    public void setTaskHandler(Consumer<SpiderDefinition> handler) {
        this.taskHandler = handler == null ? def -> {} : handler;
    }

    /**
     * 启动调度：注册扫描任务，每分钟刷新一次所有启用定时的爬虫。
     */
    @PostConstruct
    public void start() {
        if (scanFuture != null) {
            return;
        }
        scanFuture = scheduler.scheduleWithFixedDelay(
                this::rescheduleAll,
                0, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("[spider-config] SpiderTimerService 启动完成");
    }

    /**
     * 停止调度：取消所有任务与扫描线程。
     */
    @PreDestroy
    public void stop() {
        if (scanFuture != null) {
            scanFuture.cancel(false);
            scanFuture = null;
        }
        futures.values().forEach(f -> f.cancel(false));
        futures.clear();
        scheduler.shutdownNow();
        log.info("[spider-config] SpiderTimerService 已停止");
    }

    /**
     * 重新扫描所有爬虫定义，根据最新的 cron 表达式重排调度。
     *
     * <p>每分钟被扫描任务调用一次。每次只更新发生变化的任务。</p>
     */
    public void rescheduleAll() {
        try {
            SpiderDefinitionStore.PageResult<SpiderDefinition> page =
                    definitionStore.page(1, 1000, null);
            for (SpiderDefinition def : page.records()) {
                if (Integer.valueOf(1).equals(def.getSpiderStatus())
                        && Integer.valueOf(1).equals(def.getSpiderScheduleEnable())
                        && def.getSpiderScheduleCron() != null
                        && !def.getSpiderScheduleCron().isEmpty()) {
                    scheduleOrUpdate(def);
                } else {
                    cancel(def.getSpiderCode());
                }
            }
        } catch (Exception e) {
            log.error("[spider-config] 重排调度任务失败", e);
        }
    }

    /**
     * 调度或更新单个任务。
     *
     * @param def 爬虫定义
     */
    private void scheduleOrUpdate(SpiderDefinition def) {
        try {
            CronExpression cron = new CronExpression(def.getSpiderScheduleCron());
            LocalDateTime next = cron.nextAfter(LocalDateTime.now());
            if (next == null) {
                log.warn("[spider-config] 无法计算下次触发时间: spiderCode={}", def.getSpiderCode());
                return;
            }
            long delay = Duration.between(LocalDateTime.now(), next).getSeconds();
            // 取消已有任务
            cancel(def.getSpiderCode());
            ScheduledFuture<?> future = scheduler.schedule(
                    () -> {
                        try {
                            taskHandler.accept(def);
                        } catch (Exception e) {
                            log.error("[spider-config] 执行爬虫任务失败: spiderCode={}", def.getSpiderCode(), e);
                        }
                    },
                    Math.max(delay, 0), TimeUnit.SECONDS);
            futures.put(def.getSpiderCode(), future);
            if (log.isDebugEnabled()) {
                log.debug("[spider-config] 注册任务 spiderCode={} 下次触发={}", def.getSpiderCode(), next);
            }
        } catch (Exception e) {
            log.error("[spider-config] 解析 cron 失败: spiderCode={} cron={}",
                    def.getSpiderCode(), def.getSpiderScheduleCron(), e);
        }
    }

    /**
     * 取消指定爬虫的调度任务。
     *
     * @param spiderCode 爬虫编码
     */
    private void cancel(String spiderCode) {
        ScheduledFuture<?> existing = futures.remove(spiderCode);
        if (existing != null) {
            existing.cancel(false);
        }
    }
}