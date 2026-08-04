package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.task.scheduler.TimeScheduler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullUnmarked;

/**
 * 模型健康检查器 - 负责定时检查模型状态并更新健康信息
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class ModelHealthChecker {

    private final TimeScheduler scheduler;
    private final Map<ChatClient, ModelHealth> clientHealthMap = new ConcurrentHashMap<>();
    private final List<ChatClient> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Function<ChatClient, ModelHealthCheckResult> healthCheckFunction;
    private final long checkIntervalMs;

    public ModelHealthChecker(long checkIntervalMs, Function<ChatClient, ModelHealthCheckResult> healthCheckFunction) {
        this.checkIntervalMs = checkIntervalMs;
        this.healthCheckFunction = healthCheckFunction;
        this.scheduler = new TimeScheduler();
    }

    /**
     * 注册一个客户端用于健康检查
     *
     * @param client   ChatClient 实例
     * @param provider 提供商
     * @param model    模型名称
     */
    public void register(ChatClient client, String provider, String model) {
        Objects.requireNonNull(client, "client must not be null");
        ModelHealth health = new ModelHealth(provider, model);
        clientHealthMap.put(client, health);
        clients.add(client);
        log.info("[ModelHealthChecker] registered: {}:{}", provider, model);
    }

    /**
     * 启动健康检查任务
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleFixedRate("model-health-check", this::checkAllClientsHealth, checkIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("[ModelHealthChecker] started with interval {}ms", checkIntervalMs);
        }
    }

    /**
     * 停止健康检查任务
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            log.info("[ModelHealthChecker] stopped");
        }
    }

    /**
     * 获取客户端健康状态
     *
     * @param client ChatClient 实例
     * @return ModelHealth 如果不存在返回 null
     */
    public ModelHealth getHealth(ChatClient client) {
        return clientHealthMap.get(client);
    }

    /**
     * 检查客户端是否健康
     *
     * @param client ChatClient 实例
     * @return true 如果健康或未注册
     */
    public boolean isHealthy(ChatClient client) {
        ModelHealth health = clientHealthMap.get(client);
        return health == null || health.isHealthy();
    }

    /**
     * 检查客户端是否因限流而不健康
     *
     * @param client ChatClient 实例
     * @return true 如果限流
     */
    public boolean isRateLimited(ChatClient client) {
        ModelHealth health = clientHealthMap.get(client);
        return health != null && health.isRateLimited();
    }

    /**
     * 检查客户端是否因余额不足而不健康
     *
     * @param client ChatClient 实例
     * @return true 如果余额不足
     */
    public boolean isQuotaExhausted(ChatClient client) {
        ModelHealth health = clientHealthMap.get(client);
        return health != null && health.isQuotaExhausted();
    }

    /**
     * 执行健康检查
     */
    private void checkAllClientsHealth() {
        if (!running.get()) {
            return;
        }

        for (ChatClient client : clients) {
            ModelHealth health = clientHealthMap.get(client);
            if (health == null) {
                continue;
            }

            try {
                ModelHealthCheckResult result = healthCheckFunction.apply(client);
                health.setLastCheckTime(System.currentTimeMillis());

                if (result.isHealthy()) {
                    if (!health.isHealthy()) {
                        log.info("[ModelHealthChecker] client {}:{} recovered: {}",
                                health.getProvider(), health.getModel(), result.getMessage());
                        health.reset();
                    }
                } else {
                    health.setLastFailureReason(result.getMessage());
                    health.incrementConsecutiveFailures();
                    switch (result.getIssueType()) {
                        case RATE_LIMITED -> {
                            health.setRateLimited(true);
                            health.setQuotaExhausted(false);
                            log.warn("[ModelHealthChecker] client {}:{} rate limited: {} (failures: {})",
                                    health.getProvider(), health.getModel(), result.getMessage(), health.getConsecutiveFailures());
                        }
                        case QUOTA_EXHAUSTED -> {
                            health.setQuotaExhausted(true);
                            health.setRateLimited(false);
                            log.warn("[ModelHealthChecker] client {}:{} quota exhausted: {} (failures: {})",
                                    health.getProvider(), health.getModel(), result.getMessage(), health.getConsecutiveFailures());
                        }
                        default -> {
                            if (health.getConsecutiveFailures() >= 3) {
                                log.warn("[ModelHealthChecker] client {}:{} error: {} (failures: {})",
                                        health.getProvider(), health.getModel(), result.getMessage(), health.getConsecutiveFailures());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                health.setLastFailureReason(e.getMessage());
                health.incrementConsecutiveFailures();
                health.setLastCheckTime(System.currentTimeMillis());
                log.warn("[ModelHealthChecker] health check failed for {}:{}: {}",
                        health.getProvider(), health.getModel(), e.getMessage());
            }
        }
    }

    /**
     * 模型健康检查结果
     */
    public static class ModelHealthCheckResult {
        private final boolean healthy;
        private final IssueType issueType;
        private final String message;

        public ModelHealthCheckResult(boolean healthy, IssueType issueType, String message) {
            this.healthy = healthy;
            this.issueType = issueType;
            this.message = message;
        }

        public static ModelHealthCheckResult healthy() {
            return new ModelHealthCheckResult(true, IssueType.NONE, null);
        }

        public static ModelHealthCheckResult healthy(String message) {
            return new ModelHealthCheckResult(true, IssueType.NONE, message);
        }

        public static ModelHealthCheckResult rateLimited(String message) {
            return new ModelHealthCheckResult(false, IssueType.RATE_LIMITED, message);
        }

        public static ModelHealthCheckResult quotaExhausted(String message) {
            return new ModelHealthCheckResult(false, IssueType.QUOTA_EXHAUSTED, message);
        }

        public static ModelHealthCheckResult error(String message) {
            return new ModelHealthCheckResult(false, IssueType.OTHER_ERROR, message);
        }

        public boolean isHealthy() {
            return healthy;
        }

        public IssueType getIssueType() {
            return issueType;
        }

        public String getMessage() {
            return message;
        }

        public enum IssueType {
            NONE,
            RATE_LIMITED,
            QUOTA_EXHAUSTED,
            OTHER_ERROR
        }
    }
}