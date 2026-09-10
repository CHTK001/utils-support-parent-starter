package com.chua.remote.gateway;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台接入码模型（网关内存态 + 存储 SPI 持久化对象）：
 * - 控制端连接被控端 / 被控端接入平台时，必须提供平台颁发的接入码——平台校验后放行，否则拒绝
 * - 支持：过期时间（expiresAt）、接入 agent 上限（maxAgents）、白名单 IP（ipWhitelist）、接入统计（agents）
 *
 * @author AtomCode
 */
public class AccessCodeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 接入码 */
    private String code;
    /** 类型：控制端令牌 / 被控端接入码 */
    private String type;
    /** 状态：启用 / 停用 */
    private String status;
    /** 创建时间（yyyy-MM-dd HH:mm:ss） */
    private String createdAt;
    /** 过期时间（yyyy-MM-dd HH:mm:ss，null=永不过期） */
    private String expiresAt;
    /** 接入 agent 上限（0=不限） */
    private int maxAgents;
    /** 白名单 IP（逗号分隔；空=不限制） */
    private String ipWhitelist;
    /** 已接入台数 */
    private int usedAgents;
    /** 接入的 agent 统计（id/ip/时间） */
    private final List<AgentAccessStat> agents = new ArrayList<>();

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getMaxAgents() {
        return maxAgents;
    }

    public void setMaxAgents(int maxAgents) {
        this.maxAgents = maxAgents;
    }

    public String getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(String ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
    }

    public int getUsedAgents() {
        return usedAgents;
    }

    public void setUsedAgents(int usedAgents) {
        this.usedAgents = usedAgents;
    }

    public List<AgentAccessStat> getAgents() {
        return agents;
    }

    /**
     * 是否过期（expiresAt 非空且早于当前时间）。
     *
     * @return true=已过期
     */
    public boolean isExpired() {
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            java.time.LocalDateTime exp = java.time.LocalDateTime.parse(
                    expiresAt.replace(" ", "T"));
            return exp.isBefore(java.time.LocalDateTime.now());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 是否达到接入上限。
     *
     * @return true=已达上限
     */
    public boolean isFull() {
        return maxAgents > 0 && usedAgents >= maxAgents;
    }

    /**
     * 白名单 IP 校验：白名单为空=不限制；agent 上报的任一 IP 命中规则=通过（多网卡有一个对就对）。
     *
     * <p>规则格式（逗号分隔，支持）：</p>
     * <ul>
     *   <li>指定 IP：{@code 192.168.1.5}</li>
     *   <li>IP 区间：{@code 192.168.1.1-192.168.1.100}</li>
     * </ul>
     *
     * @param agentIps agent 上报的全部 IP（含连接来源 IP）
     * @return 是否放行
     */
    public boolean isAllowedIp(java.util.List<String> agentIps) {
        if (ipWhitelist == null || ipWhitelist.isBlank()) {
            return true;
        }
        if (agentIps == null || agentIps.isEmpty()) {
            return false;
        }
        for (String ip : agentIps) {
            if (ip == null || ip.isBlank()) {
                continue;
            }
            for (String rule : ipWhitelist.split(",")) {
                String r = rule.trim();
                if (r.isEmpty()) {
                    continue;
                }
                int dash = r.indexOf('-');
                if (dash > 0 && dash < r.length() - 1) {
                    // IP 区间：start-end（复用 NetUtils.ipInRange）
                    if (com.chua.common.support.network.net.NetUtils.ipInRange(ip,
                            r.substring(0, dash).trim(), r.substring(dash + 1).trim())) {
                        return true;
                    }
                } else if (ip.equals(r)) {
                    // 指定 IP：精确匹配
                    return true;
                }
            }
        }
        return false;
    }

    /** 接入 agent 统计项 */
    public static class AgentAccessStat {
        private String agentId;
        private String ip;
        private String time;

        public AgentAccessStat() {
        }

        public AgentAccessStat(String agentId, String ip, String time) {
            this.agentId = agentId;
            this.ip = ip;
            this.time = time;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }
    }
}
