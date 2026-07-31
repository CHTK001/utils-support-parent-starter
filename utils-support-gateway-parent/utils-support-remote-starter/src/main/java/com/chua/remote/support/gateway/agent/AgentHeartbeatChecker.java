package com.chua.remote.support.gateway.agent;

import com.chua.remote.support.gateway.core.router.TargetRegistry;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 心跳检查器
 *
 * <p>使用 ScheduledExecutorService 定时执行心跳检查，
 * 调用 {@link AgentRegistry#checkAndEvict()} 检测并驱逐超时的 Agent 实例。
 * 当连续多次心跳缺失时，该 Agent 将被标记为离线并从注册表中移除。
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
public class AgentHeartbeatChecker {
    /** Agent 注册表，维护所有在线 Agent 的索引 */
    private final AgentRegistry agentRegistry;
    /** 目标注册表，用于同步清理超时 Agent 关联目标 */
    private final TargetRegistry targetRegistry;
    /** 定时调度器，用于周期执行心跳检查任务 */
    private final ScheduledExecutorService scheduler;
    /** 心跳检查间隔（秒） */
    private final int checkIntervalSec;

    /**
     * 构造心跳检查器
     *
     * @param agentRegistry    Agent 注册表
     * @param checkIntervalSec 检查间隔（秒）
     */
    public AgentHeartbeatChecker(AgentRegistry agentRegistry, int checkIntervalSec) {
        this(agentRegistry, null, checkIntervalSec);
    }

    /**
     * 构造心跳检查器
     *
     * @param agentRegistry    Agent 注册表
     * @param targetRegistry   目标注册表
     * @param checkIntervalSec 检查间隔（秒）
     */
    public AgentHeartbeatChecker(AgentRegistry agentRegistry, TargetRegistry targetRegistry, int checkIntervalSec) {
        this.agentRegistry = agentRegistry;
        this.targetRegistry = targetRegistry;
        this.checkIntervalSec = checkIntervalSec;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-heartbeat");
            t.setDaemon(true); return t;
        });
    }

    /**
     * 启动心跳检查
     *
     * <p>按固定频率执行 {@link AgentRegistry#checkAndEvict()}，
     * 驱逐所有超时未心跳的 Agent 并输出警告日志。
     */
    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<String> evicted = agentRegistry.checkAndEvict();
                if (!evicted.isEmpty()) {
                    if (targetRegistry != null) {
                        evicted.forEach(targetRegistry::unregister);
                    }
                    log.warn("心跳检查: {} Agent 已注销", evicted.size());
                }
            }
 catch (Exception e) { log.error("心跳检查异常", e); }
        }, checkIntervalSec, checkIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * 停止心跳检查
     *
     * <p>立即关闭调度器，不再执行后续心跳检查任务。
     */
    public void stop() {
        scheduler.shutdownNow();
        log.info("心跳检查已停止");
    }
}
