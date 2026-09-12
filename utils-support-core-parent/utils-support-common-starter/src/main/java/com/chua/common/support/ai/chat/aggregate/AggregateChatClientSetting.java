package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.protocol.AiToken;
import com.chua.common.support.ai.context.ContextCompressionConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 聚合 ChatClient 配置（JSON 格式）
*
* <p>通过 {@link com.chua.common.support.lang.json.Json#fromJson(String, Class)} 反序列化。
* 配置示例：
* <pre>{@code
* {
*   "strategy": "hybrid",
*   "tokens": [
*     { "token": "sk-abc123", "group": "vip", "expireTime": "2026-12-31" },
*     { "token": "sk-def456", "group": "default" }
*   ],
*   "groups": [
*     {
*       "name": "primary",
*       "strategy": "weighted",
*       "tokenGroups": ["vip"],
*       "clients": [
*         { "provider": "openai", "apiKey": "sk-xxx", "model": "gpt-4", "weight": 5 }
*       ]
*     },
*     {
*       "name": "secondary",
*       "strategy": "failover",
*       "tokenGroups": ["default"],
*       "clients": [
*         { "provider": "alibaba", "apiKey": "sk-zzz", "model": "qwen-max" }
*       ]
*     }
*   ]
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AggregateChatClientSetting {

    /**
    * 全局路由策略（hybrid | failover | round_robin | weighted | cost | latency）
     */
    private String strategy = "hybrid";

    /** 是否启用调用监控 */
    private boolean monitor = true;

    /** 多组配置（仅 hybrid 策略使用） */
    private List<GroupConfig> groups;

    /** 顶级客户端列表（非 hybrid 策略使用） */
    private List<ClientConfig> clients;

    /**
    * 上下文压缩配置（可选）
     */
    private ContextCompressionConfig compression;

    /**
    * 技能描述目录路径列表（可选，用于注入 system prompt）
     */
    private List<String> skillPaths;

    /**
    * 访问令牌列表（可选，用于 RESTful 接口的 Bearer Token 认证）
     */
    private List<TokenConfig> tokens;

    /**
    * 是否启用模型健康检查（默认 false）
     */
    private boolean enableHealthCheck = false;

    /**
    * 健康检查间隔（毫秒，默认 60000）
     */
    private long healthCheckIntervalMs = 60000;

    /**
    * 限流时是否自动切换到同名的其它模型（默认 false）
     */
    private boolean autoSwitchOnRateLimit = false;

    /**
    * 余额不足时是否自动切换到同名的其它模型（默认 false）
     */
    private boolean autoSwitchOnQuotaExhausted = false;

    /**
    * 同名模型最大重试次数（默认 3）
     */
    private int maxRetriesOnSameModel = 3;

    /** 获取Compression */
    public ContextCompressionConfig getCompression() {
        return compression;
    }

    /** 获取Strategy */
    public String getStrategy() {
        return strategy;
    }

    /** 获取SkillPaths */
    public List<String> getSkillPaths() {
        return skillPaths;
    }

    /** 获取Groups */
    public List<GroupConfig> getGroups() {
        return groups;
    }

    /** 获取Clients */
    public List<ClientConfig> getClients() {
        return clients;
    }

    // ======================== 便捷方法 ========================

    /**
    * 将 tokens 配置转为 Map，便于 AiTokenServerFilter 校验。
    *
    * @return token → AiToken 映射，无 token 配置返回空 Map
     */
    public Map<String, AiToken> toTokenMap() {
        if (tokens == null || tokens.isEmpty()) {
            return Map.of();
        }
        Map<String, AiToken> map = new LinkedHashMap<>();
        for (TokenConfig tc : tokens) {
            if (tc.getToken() == null || tc.getToken().isBlank()) {
                continue;
            }
            map.put(tc.getToken(), AiToken.builder()
                    .token(tc.getToken())
                    .group(tc.getGroup() != null ? tc.getGroup() : "default")
                    .expireTime(tc.getExpireTime())
                    .enabled(true)
                    .build());
        }
        return map;
    }

    /**
    * 组配置
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupConfig {
        /**
        * 组名称
         */
        private String name;
        /**
        * 组内路由策略（failover | round_robin | weighted | cost | latency）
         */
        private String strategy = "failover";
        /** 条件表达式（如 "prompt.length < 200"），为空则默认匹配 */
        private String condition;
        /** 允许访问该组的 token 分组列表（空表示所有 token 均可访问） */
        private List<String> tokenGroups;
        /** 该组的客户端列表 */
        private List<ClientConfig> clients;

        /** 获取Name */
        public String getName() {
            return name;
        }

        /** 获取Strategy */
        public String getStrategy() {
            return strategy;
        }

        /** 获取Condition */
        public String getCondition() {
            return condition;
        }

        /** 获取TokenGroups */
        public List<String> getTokenGroups() {
            return tokenGroups;
        }

        /** 获取Clients */
        public List<ClientConfig> getClients() {
            return clients;
        }

        /**
        * 判断指定的 token 分组是否允许访问该组。
        *
        * @param tokenGroup token 分组名称
        * @return true 允许访问
         */
        public boolean isTokenGroupAllowed(String tokenGroup) {
            if (tokenGroups == null || tokenGroups.isEmpty()) {
                return true;
            }
            if (tokenGroup == null) {
                return false;
            }
            return tokenGroups.contains(tokenGroup);
        }
    }

    /**
    * 客户端配置
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClientConfig {
        /**
        * AI 服务商名称，如 "openai"、"alibaba"
         */
        private String provider;
        /**
        * API 密钥
         */
        private String apiKey;
        /**
        * 自定义 API 地址（可选）
         */
        private String baseUrl;
        /**
        * 模型名称（可选，覆盖 ChatClientSetting 中的默认值）
         */
        private String model;
        /**
        * 温度参数（可选）
         */
        private Double temperature;
        /**
        * 最大 Token 数（可选）
         */
        private Integer maxTokens;
        /**
        * 系统提示词（可选）
         */
        private String system;
        /**
        * HTTP 代理（可选）
         */
        private String proxy;
        /**
        * 权重（weighted 策略使用，默认 1）
         */
        private int weight = 1;

        /** 获取Provider */
        public String getProvider() {
            return provider;
        }

        /** 获取ApiKey */
        public String getApiKey() {
            return apiKey;
        }

        /** 获取System */
        public String getSystem() {
            return system;
        }

        /** 获取Model */
        public String getModel() {
            return model;
        }

        /** 获取Weight */
        public int getWeight() {
            return weight;
        }

        /**
        * 转换为 ChatClientSetting，通过 SPI 创建 ChatClient
        *
        * @return ChatClient 实例
         */
        public ChatClient toChatClient() {
            return ChatClient.create(toSetting());
        }

        ChatClientSetting toSetting() {
            return ChatClientSetting.builder()
                    .provider(provider).appKey(apiKey)
                    .baseUrl(baseUrl).model(model)
                    .temperature(temperature).maxTokens(maxTokens)
                    .system(system).proxy(proxy)
                    .build();
        }
    }

    /**
    * 令牌配置
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenConfig {
        /**
        * 令牌值（如 sk-xxx）
         */
        private String token;
        /**
        * 令牌分组（如 default、vip、admin）
         */
        private String group;
        /**
        * 过期时间（yyyy-MM-dd 格式），为空表示永不过期
         */
        private String expireTime;

        /** 获取Token */
        public String getToken() {
            return token;
        }

        /** 获取分组 */
        public String getGroup() {
            return group;
        }

        /**
        * 解析过期时间。
        *
        * @return Date 对象，未设置返回 null
         */
        public Date getExpireTime() {
            if (expireTime == null || expireTime.isBlank()) {
                return null;
            }
            try {
                return new SimpleDateFormat("yyyy-MM-dd").parse(expireTime);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
