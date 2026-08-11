package com.chua.common.support.taskdistribution.node;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.strategy.DispatchStrategy;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 节点注册表。
 *
 * <p>维护所有注册节点的元数据，支持按标签匹配、策略选择、心跳检测和自动剔除离线节点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class NodeTable {

    /**
     * 节点注册表：nodeId -> NodeMeta
     */
    private final Map<String, NodeMeta> nodes = new ConcurrentHashMap<>();

    /**
     * 轮询计数器
     */
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    /**
     * 轮询掩码（防止溢出）
     */
    private static final int ROUND_ROBIN_MASK = Integer.MAX_VALUE;

    /**
     * 心跳检测定时器
     */
    private final ScheduledExecutorService heartbeatScheduler;

    /**
     * 心跳超时时间（毫秒）
     */
    private static final long HEARTBEAT_TIMEOUT = 60000;

    /**
     * 心跳检测间隔（毫秒）
     */
    private static final long HEARTBEAT_CHECK_INTERVAL = 15000;

    /**
     * 构造节点注册表，默认启用心跳检测。
     */
    public NodeTable() {
        this(true);
    }

    /**
     * 构造节点注册表。
     *
     * @param enableHeartbeat 是否启用心跳检测
     */
    public NodeTable(boolean enableHeartbeat) {
        if (enableHeartbeat) {
            heartbeatScheduler = ThreadUtils.newSingleThreadScheduledExecutor(
                    ThreadUtils.newThreadFactory("node-table-heartbeat"));
            heartbeatScheduler.scheduleAtFixedRate(this::checkHeartbeats,
                    HEARTBEAT_CHECK_INTERVAL, HEARTBEAT_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        } else {
            heartbeatScheduler = null;
        }
    }

    /**
     * 注册节点（同 nodeId 覆盖，实现去重）。
     *
     * @param meta 节点元数据
     */
    public void register(NodeMeta meta) {
        if (meta == null || meta.getNodeId() == null) {
            return;
        }
        meta.setLastHeartbeat(System.currentTimeMillis());
        meta.setOnline(true);
        NodeMeta old = nodes.put(meta.getNodeId(), meta);
        if (old == null) {
            log.info("节点注册: {}", meta.getNodeId());
        } else {
            log.debug("节点更新: {}", meta.getNodeId());
        }
    }

    /**
     * 注销节点。
     *
     * @param nodeId 节点 ID
     */
    public void unregister(String nodeId) {
        NodeMeta removed = nodes.remove(nodeId);
        if (removed != null) {
            log.info("节点注销: {}", nodeId);
        }
    }

    /**
     * 心跳更新。
     *
     * @param nodeId 节点 ID
     */
    public void heartbeat(String nodeId) {
        NodeMeta meta = nodes.get(nodeId);
        if (meta != null) {
            meta.setLastHeartbeat(System.currentTimeMillis());
            meta.setOnline(true);
        }
    }

    /**
     * 获取节点。
     *
     * @param nodeId 节点 ID
     * @return 节点元数据，不存在返回 null
     */
    public NodeMeta getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * 获取所有在线节点。
     *
     * @return 在线节点列表
     */
    public List<NodeMeta> getAllNodes() {
        return nodes.values().stream()
                .filter(NodeMeta::isOnline)
                .collect(Collectors.toList());
    }

    /**
     * 按标签匹配节点。
     *
     * @param tags 目标标签
     * @return 匹配的在线节点列表
     */
    public List<NodeMeta> matchByTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return getAllNodes();
        }
        return nodes.values().stream()
                .filter(NodeMeta::isOnline)
                .filter(meta -> {
                    Map<String, String> nodeTags = meta.getTags();
                    for (Map.Entry<String, String> entry : tags.entrySet()) {
                        String nodeValue = nodeTags.get(entry.getKey());
                        if (nodeValue == null || !nodeValue.equals(entry.getValue())) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * 按任务匹配节点（使用任务标签匹配）。
     *
     * @param task 任务
     * @return 匹配的在线节点列表
     */
    public List<NodeMeta> matchByTask(Task<?> task) {
        if (task == null) {
            return Collections.emptyList();
        }
        return matchByTags(task.getTags());
    }

    /**
     * 按策略选择单节点。
     *
     * @param candidates 候选节点列表
     * @param strategy   派发策略
     * @return 选中的节点，无可选节点返回 null
     */
    public NodeMeta select(List<NodeMeta> candidates, DispatchStrategy strategy) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        switch (strategy) {
            case FIRST:
                return candidates.get(0);
            case LAST:
                return candidates.get(candidates.size() - 1);
            case RANDOM:
                return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            case ROUND:
                return roundRobinSelect(candidates);
            case WEIGHT:
                return weightedRandomSelect(candidates);
            default:
                return candidates.get(0);
        }
    }

    /**
     * 按策略选择节点，基于任务标签。
     *
     * @param task     任务
     * @param strategy 派发策略
     * @return 选中的节点
     */
    public NodeMeta selectByTask(Task<?> task, DispatchStrategy strategy) {
        List<NodeMeta> candidates = matchByTask(task);
        return select(candidates, strategy);
    }

    /**
     * 轮询选择。
     */
    private NodeMeta roundRobinSelect(List<NodeMeta> candidates) {
        int index = roundRobinCounter.getAndIncrement() & ROUND_ROBIN_MASK;
        index = index % candidates.size();
        return candidates.get(index);
    }

    /**
     * 加权随机选择。
     */
    private NodeMeta weightedRandomSelect(List<NodeMeta> candidates) {
        int totalWeight = candidates.stream().mapToInt(NodeMeta::getWeight).sum();
        if (totalWeight <= 0) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (NodeMeta candidate : candidates) {
            cumulative += candidate.getWeight();
            if (random < cumulative) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /**
     * 心跳检测：标记超时节点为离线。
     */
    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (NodeMeta meta : nodes.values()) {
            if (meta.isOnline() && (now - meta.getLastHeartbeat()) > HEARTBEAT_TIMEOUT) {
                meta.setOnline(false);
                log.warn("节点心跳超时，已标记离线: {}, 最后心跳: {}ms 前",
                        meta.getNodeId(), now - meta.getLastHeartbeat());
            }
        }
    }

    /**
     * 获取在线节点数量。
     *
     * @return 在线节点数
     */
    public int size() {
        return (int) nodes.values().stream().filter(NodeMeta::isOnline).count();
    }

    /**
     * 清空所有节点。
     */
    public void clear() {
        nodes.clear();
    }

    /**
     * 获取所有节点（包括离线）。
     *
     * @return 所有节点列表
     */
    public List<NodeMeta> getAllNodesIncludingOffline() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * 销毁心跳检测定时器。
     */
    public void destroy() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
        nodes.clear();
    }
}