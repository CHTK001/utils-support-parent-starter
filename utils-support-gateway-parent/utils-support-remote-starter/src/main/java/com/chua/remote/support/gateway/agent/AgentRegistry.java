package com.chua.remote.support.gateway.agent;

import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.remote.support.gateway.config.Protocol;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表。
 * <p>管理所有被控端 Agent 的连接、注册、心跳与生命周期。
 * 提供密钥认证、验证码管理、在线状态跟踪、心跳超时驱逐以及禁用/启用管理等功能。
 * Agent 注册时需提供正确的注册密钥，注册成功后可通过验证码被控制端发现和连接。</p>
 *
 * @author CH
 */
@Slf4j
public class AgentRegistry {

    /** Agent ID -> Agent 信息 的映射 */
    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();
    /** 网关侧可信的临时 SSH Agent 元数据，只有 bootstrap 流程可以写入 */
    private final Map<String, TemporarySshAgentMetadata> temporarySshAgents = new ConcurrentHashMap<>();
    /** 被禁用的 Agent ID 集合 */
    private final Map<String, Boolean> disabledAgents = new ConcurrentHashMap<>();
    /** 仅禁用 SOCKS5 接入的 Agent ID 集合 */
    private final Map<String, Boolean> disabledSocks5Agents = new ConcurrentHashMap<>();
    /** 网关配置，用于读取注册密钥和心跳超时参数 */
    private final GatewayProperties properties;

    /**
     * 构造 Agent 注册表。
     *
     * @param properties 网关配置属性
     */
    public AgentRegistry(GatewayProperties properties) { this.properties = properties; }

    /**
     * 处理 Agent 注册请求。
     * <p>执行密钥验证、禁用检查、ID 冲突检测，通过后创建 {@link AgentInfo} 存入注册表。
     * 验证码优先使用 Agent 指定的值，重连场景下复用旧码，最后自动生成 6 位随机码。</p>
     *
     * @param agentId     Agent ID
     * @param secret      注册密钥
     * @param channel     Agent 的 Netty 通道
     * @param protocols   支持的协议列表
     * @param transports  支持的传输协议列表
     * @param codecs      支持的编解码器列表
     * @param capabilities 能力声明（如 http_port, ssh_host 等）
     * @param verifyCode  验证码（可为空，由方法自动生成）
     * @param agentType   Agent 类型
     * @return 注册成功返回 {@link AgentInfo}，失败返回 {@code null}
     */
    public AgentInfo handleRegister(String agentId, String secret, Channel channel,
                                     List<String> protocols, List<String> transports, List<String> codecs, Map<String, String> capabilities,
                                     String verifyCode, String agentType) {
        if (!properties.getAgentRegisterKey().equals(secret)) {
            log.warn("Agent 密钥验证失败: agentId={}", agentId);
            return null;
        }
        if (disabledAgents.containsKey(agentId)) {
            log.warn("Agent 已被禁用，拒绝注册: agentId={}", agentId);
            return null;
        }
        AgentInfo existing = agents.get(agentId);
        if (existing != null && existing.isOnline() && existing.getChannel() != null && existing.getChannel().isActive()) {
            log.warn("Agent ID 已被占用: agentId={}", agentId);
            return null;
        }
        String ip = "unknown";
        int remotePort = 0;
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            ip = addr.getHostString();
            remotePort = addr.getPort();
        }
        String displayIp = resolveDisplayIp(agentId, ip);
        Map<String, String> effectiveCapabilities = trustedCapabilities(agentId, capabilities);
        String code;
        if (verifyCode != null && !verifyCode.isEmpty()) {
            code = verifyCode;
        } else if (existing != null && existing.getVerifyCode() != null && !existing.getVerifyCode().isEmpty()) {
            code = existing.getVerifyCode();
            log.info("Agent 重连，复用旧验证码: agentId={} code={}", agentId, code);
        }
 else {
            code = generateVerifyCode();
        }
        AgentInfo agent = AgentInfo.builder().agentId(agentId).secret(secret)
                .agentType(agentType != null ? agentType : "unknown")
                .protocols(protocols).transports(transports).codecs(codecs).capabilities(effectiveCapabilities)
                .registeredAt(Instant.now()).lastHeartbeatAt(Instant.now())
                .heartbeatMissCount(0).online(true).ipAddress(displayIp).verifyCode(code)
                .remotePort(remotePort).channel(channel).build();
        agents.put(agentId, agent);
        log.info("Agent 注册成功: agentId={} ip={}:{} verifyCode={} connection={}:{}",
                agentId, displayIp, remotePort, code, ip, remotePort);
        return agent;
    }

    /**
     * 标记由网关 SSH bootstrap 启动的临时 Agent。
     */
    public void markTemporarySshAgent(String agentId, String remoteHost, int sshPort,
                                      int remoteListenPort, String remoteWorkDir) {
        if (agentId == null || agentId.isBlank() || remoteHost == null || remoteHost.isBlank()) {
            return;
        }
        TemporarySshAgentMetadata metadata = new TemporarySshAgentMetadata(
                remoteHost.trim(), sshPort, remoteListenPort, remoteWorkDir);
        temporarySshAgents.put(agentId, metadata);
        AgentInfo agent = agents.get(agentId);
        if (agent != null) {
            agent.setIpAddress(metadata.remoteHost());
            agent.setCapabilities(trustedCapabilities(agentId, agent.getCapabilities()));
        }
    }

    /**
     * 清理临时 Agent 的网关侧可信元数据。
     */
    public void clearTemporarySshAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) { return; }
        temporarySshAgents.remove(agentId);
        AgentInfo agent = agents.get(agentId);
        if (agent != null) {
            agent.setCapabilities(trustedCapabilities(agentId, agent.getCapabilities()));
        }
    }

    private String resolveDisplayIp(String agentId, String fallback) {
        TemporarySshAgentMetadata metadata = temporarySshAgents.get(agentId);
        if (metadata != null && metadata.remoteHost() != null && !metadata.remoteHost().isBlank()) {
            return metadata.remoteHost();
        }
        return fallback;
    }

    private Map<String, String> trustedCapabilities(String agentId, Map<String, String> capabilities) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (capabilities != null) {
            result.putAll(capabilities);
        }
        result.remove("agent.temporary");
        result.remove("agent.trustedTemporary");
        result.remove("agent.bootstrap");
        result.remove("agent.remoteHost");
        result.remove("agent.remotePort");
        result.remove("agent.remoteListenPort");
        result.remove("agent.remoteWorkDir");
        TemporarySshAgentMetadata metadata = temporarySshAgents.get(agentId);
        if (metadata != null) {
            result.put("agent.temporary", "true");
            result.put("agent.trustedTemporary", "true");
            result.put("agent.bootstrap", "ssh");
            result.put("agent.remoteHost", metadata.remoteHost());
            result.put("agent.remotePort", String.valueOf(metadata.sshPort()));
            result.put("agent.remoteListenPort", String.valueOf(metadata.remoteListenPort()));
            if (metadata.remoteWorkDir() != null && !metadata.remoteWorkDir().isBlank()) {
                result.put("agent.remoteWorkDir", metadata.remoteWorkDir());
            }
        }
        return result;
    }

    /**
     * 处理 Agent 心跳（无 RTT 版本）。
     *
     * @param agentId    Agent ID
     * @param verifyCode 心跳携带的验证码
     */
    public void handleHeartbeat(String agentId, String verifyCode) {
        handleHeartbeat(agentId, verifyCode, -1);
    }

    /**
     * 处理 Agent 心跳。
     * <p>更新最后心跳时间和验证码，重置心跳丢失计数。
     * 如果心跳中携带的验证码与记录不一致，自动更新为最新值。</p>
     *
     * @param agentId    Agent ID
     * @param verifyCode 心跳携带的验证码
     * @param rttMs      本轮心跳往返时延（毫秒），负数表示不更新
     */
    public void handleHeartbeat(String agentId, String verifyCode, long rttMs) {
        AgentInfo a = agents.get(agentId);
        if (a != null) {
            a.setLastHeartbeatAt(Instant.now());
            a.setHeartbeatMissCount(0);
            if (rttMs >= 0) { a.setLastRttMs(rttMs); }
            if (verifyCode != null && !verifyCode.isEmpty() && !verifyCode.equals(a.getVerifyCode())) {
                log.info("Agent 验证码已更新: agentId={} newCode={}", agentId, verifyCode);
                a.setVerifyCode(verifyCode);
            }
        }
    }

    /**
     * 通过验证码查找在线 Agent。
     * <p>用于控制端输入验证码后快速定位对应的 Agent。</p>
     *
     * @param verifyCode 6 位验证码
     * @return 匹配的在线 Agent，未找到时返回 {@code null}
     */
    public AgentInfo findByVerifyCode(String verifyCode) {
        if (verifyCode == null || verifyCode.isEmpty()) { return null; }
        return agents.values().stream()
                .filter(AgentInfo::isOnline)
                .filter(a -> verifyCode.equals(a.getVerifyCode()))
                .findFirst().orElse(null);
    }

    /**
     * 生成 6 位随机验证码。
     *
     * @return 6 位十六进制验证码
     */
    private String generateVerifyCode() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /**
     * 检查并驱逐心跳超时的 Agent。
     * <p>遍历所有 Agent，计算距上次心跳的时长。超过超时阈值时增加丢失计数，
     * 当丢失计数达到上限后标记为离线并从注册表中移除。</p>
     *
     * @return 被驱逐的 Agent ID 列表
     */
    public List<String> checkAndEvict() {
        List<String> evicted = new ArrayList<>();
        Instant now = Instant.now();
        int timeoutSec = properties.getAgentHeartbeatInterval() * properties.getMaxHeartbeatMisses();
        for (Map.Entry<String, AgentInfo> e : agents.entrySet()) {
            long elapsed = Duration.between(e.getValue().getLastHeartbeatAt(), now).getSeconds();
            if (elapsed > timeoutSec) {
                e.getValue().setHeartbeatMissCount(e.getValue().getHeartbeatMissCount() + 1);
                if (e.getValue().getHeartbeatMissCount() >= properties.getMaxHeartbeatMisses()) {
                    e.getValue().setOnline(false);
                    evicted.add(e.getKey());
                    log.warn("Agent 心跳超时: agentId={} elapsed={}s", e.getKey(), elapsed);
                }
            }
        }
        evicted.forEach(agents::remove);
        return evicted;
    }

    /**
     * 获取 Agent 信息。
     *
     * @param agentId Agent ID
     * @return Agent 信息，不存在时返回 {@code null}
     */
    public AgentInfo getAgent(String agentId) { return agents.get(agentId); }

    /**
     * 获取 Agent 的 Netty 通道。
     *
     * @param agentId Agent ID
     * @return Agent 的通道，离线或不存在时返回 {@code null}
     */
    public Channel getAgentChannel(String agentId) {
        AgentInfo a = agents.get(agentId);
        return a != null && a.isOnline() ? a.getChannel() : null;
    }

    /**
     * 根据协议查找在线 Agent。
     *
     * @param protocol 协议类型
     * @return 支持该协议的第一个在线 Agent，未找到时返回 {@code null}
     */
    public AgentInfo findAgentByProtocol(Protocol protocol) {
        String name = protocol.name();
        return agents.values().stream()
                .filter(AgentInfo::isOnline)
                .filter(a -> a.getProtocols() != null && a.getProtocols().stream().anyMatch(p -> p.equalsIgnoreCase(name)))
                .findFirst().orElse(null);
    }

    /**
     * 根据 Netty 通道查找 Agent。
     *
     * @param channel 通道
     * @return 绑定该通道的 Agent，未找到时返回 {@code null}
     */
    public AgentInfo findByChannel(Channel channel) {
        if (channel == null) { return null; }
        return agents.values().stream()
                .filter(a -> channel.equals(a.getChannel()))
                .findFirst().orElse(null);
    }

    /**
     * 注销 Agent。
     *
     * @param agentId Agent ID
     */
    public void unregister(String agentId) { agents.remove(agentId); }

    /**
     * 获取在线 Agent 数量。
     *
     * @return 在线 Agent 数
     */
    public int onlineCount() { return (int) agents.values().stream().filter(AgentInfo::isOnline).count(); }

    /**
     * 获取所有 Agent 数量（含离线）。
     *
     * @return 总 Agent 数
     */
    public int totalCount() { return agents.size(); }

    /**
     * 获取全部 Agent 快照。
     *
     * @return Agent 列表
     */
    public List<AgentInfo> allAgents() { return new ArrayList<>(agents.values()); }

    /**
     * 设置 Agent 的 SOCKS5 接入开关。
     *
     * @param agentId Agent ID
     * @param enabled 是否允许 SOCKS5 接入
     */
    public void setSocks5AccessEnabled(String agentId, boolean enabled) {
        if (agentId == null || agentId.isEmpty()) { return; }
        if (enabled) {
            disabledSocks5Agents.remove(agentId);
        }
 else {
            disabledSocks5Agents.put(agentId, true);
        }
        log.info("Agent SOCKS5 接入已{}: agentId={}", enabled ? "启用" : "停止", agentId);
    }

    /**
     * 检查 Agent 是否允许 SOCKS5 接入。
     *
     * @param agentId Agent ID
     * @return true 表示允许 SOCKS5 接入
     */
    public boolean isSocks5AccessEnabled(String agentId) {
        return agentId != null && !disabledSocks5Agents.containsKey(agentId);
    }

    // ===== 禁用/启用管理 =====

    /**
     * 禁用 Agent。
     * <p>在网关层面阻止该 Agent 的连接，如果 Agent 当前在线则主动关闭其通道。</p>
     *
     * @param agentId 要禁用的 Agent ID
     */
    public void disableAgent(String agentId) {
        disabledAgents.put(agentId, true);
        AgentInfo a = agents.get(agentId);
        if (a != null) {
            a.setDisabled(true);
            Channel ch = a.getChannel();
            if (ch != null && ch.isActive()) {
                ch.close();
            }
            log.info("Agent 已禁用: agentId={}", agentId);
        }
 else {
            log.info("Agent 已禁用（未注册）: agentId={}", agentId);
        }
    }

    /**
     * 启用 Agent。
     *
     * @param agentId 要启用的 Agent ID
     */
    public void enableAgent(String agentId) {
        disabledAgents.remove(agentId);
        AgentInfo a = agents.get(agentId);
        if (a != null) {
            a.setDisabled(false);
        }
        log.info("Agent 已启用: agentId={}", agentId);
    }

    /**
     * 检查 Agent 是否被禁用。
     *
     * @param agentId Agent ID
     * @return true 表示已被禁用
     */
    public boolean isDisabled(String agentId) {
        return disabledAgents.containsKey(agentId);
    }

    private record TemporarySshAgentMetadata(String remoteHost, int sshPort,
                                             int remoteListenPort, String remoteWorkDir) {}
}
