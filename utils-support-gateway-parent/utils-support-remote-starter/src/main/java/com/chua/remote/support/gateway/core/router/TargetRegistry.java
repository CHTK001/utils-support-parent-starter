package com.chua.remote.support.gateway.core.router;

import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.config.Protocol;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 目标注册表。
 * <p>管理所有可用的远程目标节点（Target），每个 Target 代表一个可通过网关访问的远程服务端点。
 * 支持根据 Agent 的注册信息自动创建目标条目，同一 Agent 的不同协议和传输组合会生成独立的目标 ID，
 * 实现从控制端到被控端的透明路由。</p>
 *
 * @author CH
 */
@Slf4j
public class TargetRegistry {

    /** 目标 ID -> 目标条目 的映射 */
    private final Map<String, TargetEntry> targets = new ConcurrentHashMap<>();
    /** Agent ID -> Agent 信息 的映射 */
    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();

    /**
     * 生成短目标 ID。</p>
     *
     * @param agentId  Agent ID
     * @param protocol 协议名称（如 SSH, DESKTOP, HTTP）
     * @param transport 传输协议
     * @return 12 位十六进制目标 ID
     */
    private static String generateTargetId(String agentId, String protocol, String transport) {
        String raw = agentId + "-" + protocol.toUpperCase() + "-" + transport.toUpperCase();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(raw.getBytes());
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i] & 0xff));
            }
            return sb.toString();
        }
 catch (NoSuchAlgorithmException e) {
            return raw.length() > 12 ? raw.substring(0, 12) : raw;
        }
    }

    /**
     * 注册批量目标条目。
     * <p>将 Agent 信息及其提供的所有目标条目一并注册。</p>
     *
     * @param agent   Agent 信息
     * @param entries 该 Agent 提供的目标条目列表
     */
    public void register(AgentInfo agent, List<TargetEntry> entries) {
        agents.put(agent.getAgentId(), agent);
        for (TargetEntry entry : entries) {
            entry.setAgentId(agent.getAgentId());
            targets.put(entry.getTargetId(), entry);
            log.info("Target 注册: {} -> {}:{} (agent={})", entry.getTargetId(), entry.getHost(), entry.getPort(), agent.getAgentId());
        }
    }

    /**
     * 同步 Agent 信息到目标注册表（无主机地址版本，默认使用 "unknown"）。
     *
     * @param agent Agent 信息
     */
    public void syncAgent(AgentInfo agent) {
        syncAgent(agent, "unknown");
    }

    /**
     * 同步 Agent 信息到目标注册表。
     * <p>根据 Agent 上报的协议列表和传输协议列表，自动为每个协议×传输组合创建目标条目。
     * 已有目标不会重复创建。对于 HTTP 和 SSH 协议，会从 capabilities 中读取端口和地址信息。</p>
     *
     * @param agent     Agent 信息
     * @param agentHost Agent 所在主机地址
     */
    public void syncAgent(AgentInfo agent, String agentHost) {
        agents.put(agent.getAgentId(), agent);
        List<String> transports = agent.getTransports();
        if (transports == null || transports.isEmpty()) {
            transports = List.of("TCP");
        }
        if (agent.getProtocols() != null) {
            for (String protoName : agent.getProtocols()) {
                try {
                    Protocol proto = Protocol.valueOf(protoName.toUpperCase());
                    for (String transport : transports) {
                        String targetId = generateTargetId(agent.getAgentId(), protoName, transport);
                        if (!targets.containsKey(targetId)) {
                            String host = "0.0.0.0";
                            int port = 0;
                            Map<String, String> caps = agent.getCapabilities();
                            if (caps != null) {
                                if (proto == Protocol.HTTP) {
                                    String httpPortStr = caps.get("http_port");
                                    if (httpPortStr != null) {
                                        try {
                                            port = Integer.parseInt(httpPortStr);
                                            host = agentHost;
                                        }
 catch (NumberFormatException ignored) {}
                                    }
                                } else if (proto == Protocol.SSH) {
                                    String sshHost = caps.get("ssh_host");
                                    String sshPort = caps.get("ssh_port");
                                    if (sshHost != null) { host = sshHost; }
                                    if (sshPort != null) {
                                        try { port = Integer.parseInt(sshPort); }

                                        catch (NumberFormatException ignored) {}
                                    }
                                } else if (proto == Protocol.RUSTDESK) {
                                    String rdPort = caps.get("rustdesk_port");
                                    host = agentHost;
                                    if (rdPort != null) {
                                        try { port = Integer.parseInt(rdPort); }

                                        catch (NumberFormatException ignored) {}
                                    }
                                }
                            }
                            TargetEntry entry = TargetEntry.builder()
                                    .targetId(targetId)
                                    .agentId(agent.getAgentId())
                                    .host(host)
                                    .port(port)
                                    .protocol(proto)
                                    .transport(transport)
                                    .registeredAt(Instant.now())
                                    .build();
                            targets.put(targetId, entry);
                            log.info("Target 自动注册: {} (protocol={}, transport={}, agent={}, host={}, port={})",
                                    targetId, protoName, transport, agent.getAgentId(), host, port);
                        }
                    }
                }
 catch (IllegalArgumentException e) {
                    log.debug("跳过未知协议: {}", protoName);
                }
            }
        }
        log.info("Agent 同步到 TargetRegistry: agentId={}", agent.getAgentId());
    }

    /**
     * 注销 Agent 及其关联的所有目标。
     *
     * @param agentId Agent ID
     */
    public void unregister(String agentId) {
        agents.remove(agentId);
        targets.values().removeIf(t -> agentId.equals(t.getAgentId()));
        log.warn("Agent 已注销: {}", agentId);
    }

    /**
     * 根据目标 ID 查找目标条目。
     *
     * @param targetId 目标 ID
     * @return 目标条目，不存在时返回 {@code null}
     */
    public TargetEntry lookup(String targetId) { return targets.get(targetId); }

    /**
     * 按协议类型查找所有目标。
     *
     * @param protocol 协议类型
     * @return 匹配该协议的目标列表
     */
    public List<TargetEntry> lookupByProtocol(Protocol protocol) {
        return targets.values().stream().filter(t -> t.getProtocol() == protocol).collect(Collectors.toList());
    }

    /**
     * 按 Agent ID 查找所有关联的目标。
     *
     * @param agentId Agent ID
     * @return 关联该 Agent 的目标列表
     */
    public List<TargetEntry> lookupByAgentId(String agentId) {
        return targets.values().stream().filter(t -> agentId.equals(t.getAgentId())).collect(Collectors.toList());
    }

    /**
     * 获取 Agent 信息。
     *
     * @param agentId Agent ID
     * @return Agent 信息，不存在时返回 {@code null}
     */
    public AgentInfo getAgent(String agentId) { return agents.get(agentId); }

    /**
     * 获取所有在线 Agent 列表。
     *
     * @return 在线的 Agent 信息列表
     */
    public List<AgentInfo> onlineAgents() { return agents.values().stream().filter(AgentInfo::isOnline).collect(Collectors.toList()); }

    /**
     * 获取所有目标条目的只读快照。
     *
     * @return 所有目标条目的列表
     */
    public List<TargetEntry> allTargets() { return List.copyOf(targets.values()); }

    /**
     * 获取已注册的 Agent 数量。
     *
     * @return Agent 数量
     */
    public int agentCount() { return agents.size(); }

    /**
     * 获取已注册的目标数量。
     *
     * @return 目标数量
     */
    public int targetCount() { return targets.size(); }
}
