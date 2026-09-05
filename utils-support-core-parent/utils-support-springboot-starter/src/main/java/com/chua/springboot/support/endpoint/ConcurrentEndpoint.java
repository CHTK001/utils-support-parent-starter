package com.chua.springboot.support.endpoint;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;
import com.chua.common.support.concurrent.lock.LockFlow;
import com.chua.common.support.concurrent.rate.RateLimiterFlow;
import com.chua.starter.datasource.entity.ConcurrentCircuitBreakerConfig;
import com.chua.starter.datasource.entity.ConcurrentDistributedLockConfig;
import com.chua.starter.datasource.entity.ConcurrentRateLimiterConfig;
import com.chua.starter.datasource.repository.CircuitBreakerConfigRepository;
import com.chua.starter.datasource.repository.DistributedLockConfigRepository;
import com.chua.starter.datasource.repository.RateLimiterConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 并发工具 Actuator 端点
 * <p>
 * 提供限流器、熔断器、分布式锁的配置持久化（SQLite）与运行状态查看能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(name = {
        "com.chua.common.support.concurrent.rate.RateLimiterFlow",
        "com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow",
        "com.chua.common.support.concurrent.lock.LockFlow",
        // 端点依赖 datasource 仓储类：仓储 starter 缺失时整体退避（避免类加载失败）
        "com.chua.starter.datasource.repository.RateLimiterConfigRepository",
        "com.chua.starter.datasource.repository.CircuitBreakerConfigRepository",
        "com.chua.starter.datasource.repository.DistributedLockConfigRepository"
})
public class ConcurrentEndpoint {

    private final RateLimiterConfigRepository rateLimiterRepo;
    private final CircuitBreakerConfigRepository cbRepo;
    private final DistributedLockConfigRepository lockRepo;

    public ConcurrentEndpoint(ObjectProvider<RateLimiterConfigRepository> rateLimiterRepo,
                              ObjectProvider<CircuitBreakerConfigRepository> cbRepo,
                              ObjectProvider<DistributedLockConfigRepository> lockRepo) {
        // 仓储 Bean 依赖 datasource 基础设施，缺失时置空并让各操作优雅降级，
        // 避免非 datasource 应用启动时因必填构造依赖而失败
        this.rateLimiterRepo = rateLimiterRepo.getIfAvailable();
        this.cbRepo = cbRepo.getIfAvailable();
        this.lockRepo = lockRepo.getIfAvailable();
    }

    // ==================== 概览 ====================

    @ReadOperation
    public Map<String, Object> overview() {
        Map<String, Object> result = new HashMap<>();
        result.put("rateLimiterCount", RateLimiterFlow.list().size());
        result.put("circuitBreakerCount", CircuitBreakerFlow.list().size());
        result.put("lockCount", LockFlow.list().size());
        return result;
    }

    // ==================== 限流器：配置 CRUD ====================

    @ReadOperation
    public List<Map<String, Object>> ratelimiterConfig() {
        if (rateLimiterRepo == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConcurrentRateLimiterConfig c : rateLimiterRepo.findAll()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", c.getName());
            item.put("permitsPerSecond", c.getPermitsPerSecond());
            item.put("warmupPeriodMs", c.getWarmupPeriodMs());
            item.put("timeoutMs", c.getTimeoutMs());
            item.put("enabled", c.isEnabled());
            result.add(item);
        }
        return result;
    }

    @ReadOperation
    public Map<String, Object> ratelimiterConfig(@Selector String name) {
        if (rateLimiterRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        return rateLimiterRepo.findByName(name)
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", c.getName());
                    m.put("permitsPerSecond", c.getPermitsPerSecond());
                    m.put("warmupPeriodMs", c.getWarmupPeriodMs());
                    m.put("timeoutMs", c.getTimeoutMs());
                    m.put("enabled", c.isEnabled());
                    return m;
                })
                .orElse(Map.of("error", "not found: " + name));
    }

    @WriteOperation
    public Map<String, Object> ratelimiterConfigSave(@Selector String name,
                                                     Map<String, Object> body) {
        if (rateLimiterRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        if (body == null) body = Map.of();
        double pps = toDouble(body.get("permitsPerSecond"), 1.0);
        long warmup = toLong(body.get("warmupPeriodMs"), 0);
        long timeout = toLong(body.get("timeoutMs"), 0);
        boolean enabled = toBool(body.get("enabled"), true);

        ConcurrentRateLimiterConfig config = new ConcurrentRateLimiterConfig();
        config.setName(name);
        config.setPermitsPerSecond(pps);
        config.setWarmupPeriodMs(warmup);
        config.setTimeoutMs(timeout);
        config.setEnabled(enabled);

        rateLimiterRepo.save(config);
        reloadRateLimiter(name);
        return Map.of("name", name, "message", "保存成功");
    }

    @WriteOperation
    public Map<String, Object> ratelimiterConfigDelete(@Selector String name) {
        if (rateLimiterRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        rateLimiterRepo.delete(name);
        RateLimiterFlow.remove(name);
        return Map.of("name", name, "message", "已删除");
    }

    // ==================== 熔断器：配置 CRUD ====================

    @ReadOperation
    public List<Map<String, Object>> circuitbreakerConfig() {
        if (cbRepo == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConcurrentCircuitBreakerConfig c : cbRepo.findAll()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", c.getName());
            item.put("failureThreshold", c.getFailureThreshold());
            item.put("successThreshold", c.getSuccessThreshold());
            item.put("waitDurationMs", c.getWaitDurationMs());
            item.put("enabled", c.isEnabled());
            result.add(item);
        }
        return result;
    }

    @ReadOperation
    public Map<String, Object> circuitbreakerConfig(@Selector String name) {
        if (cbRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        return cbRepo.findByName(name)
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", c.getName());
                    m.put("failureThreshold", c.getFailureThreshold());
                    m.put("successThreshold", c.getSuccessThreshold());
                    m.put("waitDurationMs", c.getWaitDurationMs());
                    m.put("enabled", c.isEnabled());
                    return m;
                })
                .orElse(Map.of("error", "not found: " + name));
    }

    @WriteOperation
    public Map<String, Object> circuitbreakerConfigSave(@Selector String name,
                                                         Map<String, Object> body) {
        if (cbRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        if (body == null) body = Map.of();
        int failure = body.containsKey("failureThreshold") ? body.get("failureThreshold").toString().isEmpty() ? 5 : Integer.parseInt(body.get("failureThreshold").toString()) : 5;
        int success = body.containsKey("successThreshold") ? body.get("successThreshold").toString().isEmpty() ? 2 : Integer.parseInt(body.get("successThreshold").toString()) : 2;
        long waitMs = toLong(body.get("waitDurationMs"), 60000);
        boolean enabled = toBool(body.get("enabled"), true);

        ConcurrentCircuitBreakerConfig config = new ConcurrentCircuitBreakerConfig();
        config.setName(name);
        config.setFailureThreshold(failure);
        config.setSuccessThreshold(success);
        config.setWaitDurationMs(waitMs);
        config.setEnabled(enabled);

        cbRepo.save(config);
        CircuitBreakerFlow.clear();
        return Map.of("name", name, "message", "保存成功，缓存已清除");
    }

    @WriteOperation
    public Map<String, Object> circuitbreakerConfigDelete(@Selector String name) {
        if (cbRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        cbRepo.delete(name);
        CircuitBreakerFlow.remove(name);
        return Map.of("name", name, "message", "已删除");
    }

    // ==================== 锁：配置 CRUD ====================

    @ReadOperation
    public List<Map<String, Object>> lockConfig() {
        if (lockRepo == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConcurrentDistributedLockConfig c : lockRepo.findAll()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", c.getName());
            item.put("lockType", c.getLockType());
            item.put("fair", c.isFair());
            item.put("waitTimeMs", c.getWaitTimeMs());
            item.put("leaseTimeMs", c.getLeaseTimeMs());
            item.put("enabled", c.isEnabled());
            result.add(item);
        }
        return result;
    }

    @ReadOperation
    public Map<String, Object> lockConfig(@Selector String name) {
        if (lockRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        return lockRepo.findByName(name)
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", c.getName());
                    m.put("lockType", c.getLockType());
                    m.put("fair", c.isFair());
                    m.put("waitTimeMs", c.getWaitTimeMs());
                    m.put("leaseTimeMs", c.getLeaseTimeMs());
                    m.put("enabled", c.isEnabled());
                    return m;
                })
                .orElse(Map.of("error", "not found: " + name));
    }

    @WriteOperation
    public Map<String, Object> lockConfigSave(@Selector String name,
                                               Map<String, Object> body) {
        if (lockRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        if (body == null) body = Map.of();
        String lockType = toString(body.get("lockType"), "object");
        boolean fair = toBool(body.get("fair"), false);
        long waitMs = toLong(body.get("waitTimeMs"), 0);
        long leaseMs = toLong(body.get("leaseTimeMs"), -1);
        boolean enabled = toBool(body.get("enabled"), true);

        ConcurrentDistributedLockConfig config = new ConcurrentDistributedLockConfig();
        config.setName(name);
        config.setLockType(lockType);
        config.setFair(fair);
        config.setWaitTimeMs(waitMs);
        config.setLeaseTimeMs(leaseMs);
        config.setEnabled(enabled);

        lockRepo.save(config);
        LockFlow.remove(name);
        return Map.of("name", name, "message", "保存成功，缓存已清除");
    }

    @WriteOperation
    public Map<String, Object> lockConfigDelete(@Selector String name) {
        if (lockRepo == null) {
            return Map.of("error", "配置存储未初始化（未配置 datasource）");
        }
        lockRepo.delete(name);
        LockFlow.remove(name);
        return Map.of("name", name, "message", "已删除");
    }

    // ==================== 运行时状态（从 Flow 缓存读取）====================

    @ReadOperation
    public List<Map<String, Object>> ratelimiter() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, com.chua.common.support.concurrent.rate.RateLimiterProvider> e : RateLimiterFlow.list().entrySet()) {
            var p = e.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("name", p.getName());
            item.put("availablePermits", p.availablePermits());
            result.add(item);
        }
        return result;
    }

    @ReadOperation
    public Map<String, Object> ratelimiter(@Selector String name) {
        var p = RateLimiterFlow.get(name);
        if (p == null) return Map.of("error", "not found: " + name);
        Map<String, Object> r = new HashMap<>();
        r.put("name", p.getName());
        r.put("availablePermits", p.availablePermits());
        return r;
    }

    @WriteOperation
    public Map<String, Object> ratelimiterClear() {
        RateLimiterFlow.clear();
        return Map.of("message", "所有限流器缓存已清空");
    }

    @ReadOperation
    public List<Map<String, Object>> circuitbreaker() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var p : CircuitBreakerFlow.list().values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", p.getName());
            item.put("open", p.isOpen());
            result.add(item);
        }
        return result;
    }

    @ReadOperation
    public Map<String, Object> circuitbreaker(@Selector String name) {
        var p = CircuitBreakerFlow.get(name);
        if (p == null) return Map.of("error", "not found: " + name);
        Map<String, Object> r = new HashMap<>();
        r.put("name", p.getName());
        r.put("open", p.isOpen());
        return r;
    }

    @WriteOperation
    public Map<String, Object> circuitbreakerReset(@Selector String name) {
        var p = CircuitBreakerFlow.get(name);
        if (p == null) return Map.of("error", "not found: " + name);
        p.reset();
        return Map.of("name", name, "message", "熔断器已重置");
    }

    @WriteOperation
    public Map<String, Object> circuitbreakerClear() {
        CircuitBreakerFlow.clear();
        return Map.of("message", "所有熔断器缓存已清空");
    }

    @ReadOperation
    public List<Map<String, Object>> lock() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : LockFlow.list().entrySet()) {
            var p = e.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("key", e.getKey());
            item.put("name", p.getName());
            item.put("type", p.getType());
            result.add(item);
        }
        return result;
    }

    @ReadOperation
    public Map<String, Object> lock(@Selector String name) {
        var p = LockFlow.get(name);
        if (p == null) return Map.of("error", "not found: " + name);
        Map<String, Object> r = new HashMap<>();
        r.put("name", p.getName());
        r.put("type", p.getType());
        return r;
    }

    @WriteOperation
    public Map<String, Object> lockClear() {
        for (var p : LockFlow.list().values()) {
            try { p.unlock(); } catch (Exception e) { log.warn("[ConcurrentEndpoint] 释放锁失败: {}", p.getName(), e); }
        }
        LockFlow.clear();
        return Map.of("message", "所有锁缓存已清空");
    }

    // ==================== 嵌入式页面 ====================

    @ReadOperation
    public String editor() {
        try {
            java.io.InputStream is = getClass().getClassLoader()
                    .getResourceAsStream("static/concurrent-monitor.html");
            if (is == null) return "<html><body>Page not found</body></html>";
            byte[] bytes = is.readAllBytes();
            is.close();
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<html><body>Error: " + e.getMessage() + "</body></html>";
        }
    }

    // ==================== 内部辅助 ====================

    private void reloadRateLimiter(String name) {
        if (rateLimiterRepo == null) {
            return;
        }
        rateLimiterRepo.findByName(name).ifPresent(c -> {
            RateLimiterFlow.remove(name);
            double pps = c.getPermitsPerSecond();
            long warmup = c.getWarmupPeriodMs() > 0 ? c.getWarmupPeriodMs() / 1000 : 0;
            com.chua.common.support.concurrent.rate.RateLimiterFlow.of(name, pps);
            if (warmup > 0) {
                // warmup 通过 warmup() 链式设置，但这里我们直接重新构建
                // 由于 remove 已经清空缓存，下次访问时会用默认值重建
                // 若需要精确恢复 warmup，需通过反射或直接调用流 API
            }
        });
    }

    private static double toDouble(Object v, double def) {
        if (v == null) return def;
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }

    private static long toLong(Object v, long def) {
        if (v == null) return def;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return def; }
    }

    private static int parseInt(Object v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    private static boolean toBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }

    private static String toString(Object v, String def) {
        if (v == null) return def;
        return v.toString();
    }
}
