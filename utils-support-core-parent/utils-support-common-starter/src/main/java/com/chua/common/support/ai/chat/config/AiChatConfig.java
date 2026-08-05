package com.chua.common.support.ai.chat.config;

import com.chua.common.support.ai.chat.aggregate.AggregateChatClient;
import com.chua.common.support.ai.chat.aggregate.AggregateChatClientSetting;
import com.chua.common.support.ai.context.ContextCompressionConfig;
import com.chua.common.support.lang.datasource.engine.Engine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 对话配置链式 Builder — 以 fluent API 替代 JSON/YAML 配置。
 *
 * <p>通过链式调用构建 {@link AggregateChatClientSetting}，再通过 Engine ORM 持久化。
 *
 * <p>使用示例：
 * <pre>{@code
 *   // 链式构建配置
 *   AiChatConfig config = AiChatConfig.configure()
 *       .strategy("hybrid")
 *       .group("primary").strategy("round_robin")
 *           .add("openai", "sk-xxx").model("gpt-4").weight(5).back()
 *           .add("openai", "sk-yyy").model("gpt-4").weight(3).back()
 *       .group("fallback").strategy("failover")
 *           .add("alibaba", "sk-zzz").model("qwen-max").back()
 *       .build();
 *
 *   // 转为 AggregateChatClientSetting
 *   AggregateChatClientSetting setting = config.toSetting();
 *   ChatClient client = new AggregateChatClient(setting);
 *
 *   // 通过 Engine 持久化
 *   engine.update(AiChatConfigEntity.class).saveOrUpdate(config.toEntity());
 *
 *   // 从 Engine 加载
 *   AiChatConfigEntity entity = engine.query(AiChatConfigEntity.class).eq("name", "prod").one();
 *   ChatClient client2 = new AggregateChatClient(entity.toConfig().toSetting());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Slf4j
public class AiChatConfig {

    /**
     * 策略名称
     */
    private String strategy = "hybrid";
    private ContextCompressionConfig compression;
    private List<String> skillPaths;
    private final List<GroupBuilder> groups = new ArrayList<>();

    /** 当前 GroupBuilder（用于 add() 时直接添加到当前组） */
    private transient GroupBuilder currentGroup;
    /** 是否已从 Engine 加载/写入 */
    private transient volatile boolean loaded = false;
    /** 加载时使用的名称 */
    private transient String loadedName;

    // ======================== 顶级 API ========================

    public static AiChatConfig configure() {
        return new AiChatConfig();
    }

    /** 设置全局策略 */
    public AiChatConfig strategy(String strategy) {
        this.strategy = strategy;
        return this;
    }

    /** 设置上下文压缩配置 */
    public AiChatConfig compression(ContextCompressionConfig compression) {
        this.compression = compression;
        return this;
    }

    /** 设置技能目录路径列表 */
    public AiChatConfig skillPaths(List<String> skillPaths) {
        this.skillPaths = skillPaths;
        return this;
    }

    /** 开始一个新组 */
    public GroupBuilder group(String name) {
        currentGroup = new GroupBuilder(this, name);
        groups.add(currentGroup);
        return currentGroup;
    }

    /** 构建完成，返回自身（可链式调 toSetting()） */
    public AiChatConfig build() {
        return this;
    }

    /** 转换为 AggregateChatClientSetting */
    public AggregateChatClientSetting toSetting() {
        AggregateChatClientSetting setting = new AggregateChatClientSetting();
        setting.setStrategy(strategy);
        setting.setCompression(compression);
        setting.setSkillPaths(skillPaths);

        List<AggregateChatClientSetting.GroupConfig> groupConfigs = new ArrayList<>();
        for (GroupBuilder gb : groups) {
            groupConfigs.add(gb.toGroupConfig());
        }
        setting.setGroups(groupConfigs);
        return setting;
    }

    /** 转换为配置头实体 */
    public AiChatConfigEntity toHeader(String name) {
        AiChatConfigEntity header = new AiChatConfigEntity();
        header.setName(name);
        header.setStrategy(strategy);
        header.setMonitor(true);
        header.setUpdatedAt(System.currentTimeMillis());
        return header;
    }

    /** 转换为客户端绑定实体列表 */
    public List<AiChatClientBindingEntity> toBindings(Long configId) {
        List<AiChatClientBindingEntity> result = new ArrayList<>();
        int groupOrder = 0;
        for (GroupBuilder gb : groups) {
            int clientOrder = 0;
            for (ClientBuilder cb : gb.clients) {
                AiChatClientBindingEntity be = new AiChatClientBindingEntity();
                be.setConfigId(configId);
                be.setGroupName(gb.name);
                be.setGroupStrategy(gb.strategy);
                be.setGroupCondition(gb.condition);
                be.setGroupOrder(groupOrder);
                be.setProvider(cb.provider);
                be.setApiKey(cb.apiKey);
                be.setBaseUrl(cb.baseUrl);
                be.setModel(cb.model);
                be.setTemperature(cb.temperature);
                be.setMaxTokens(cb.maxTokens);
                be.setSystemPrompt(cb.system);
                be.setProxy(cb.proxy);
                be.setWeight(cb.weight);
                be.setClientOrder(clientOrder++);
                be.setCreatedAt(System.currentTimeMillis());
                be.setUpdatedAt(System.currentTimeMillis());
                result.add(be);
            }
            groupOrder++;
        }
        return result;
    }

    // ======================== Engine 持久化 ========================

    /**
     * 从 Engine 加载配置（惰性加载 + 缓存）。
     *
     * <p>首次调用且 Engine 中已有数据 → 从 Engine 加载覆盖当前配置。
     * 首次调用且 Engine 中无数据 → 将当前配置写入 Engine。
     * 后续调用（已加载）→ 直接跳过，无副作用。
     *
     * @param engine Engine 实例
     * @param name   配置名称
     * @return 自身（支持链式）
     */
    public AiChatConfig loadFromEngine(Engine engine, String name) {
        if (engine == null || name == null || name.isBlank()) return this;
        if (loaded && name.equals(loadedName)) return this;

        AiChatConfigEntity existing = engine.query(AiChatConfigEntity.class)
                .eq("name", name).one();
        if (existing != null) {
            // 从 Engine 加载
            List<AiChatClientBindingEntity> bindings = engine.query(AiChatClientBindingEntity.class)
                    .eq("configId", existing.getId())
                    .orderByAsc("groupOrder, clientOrder")
                    .list();
            AiChatConfig loaded = fromEntity(existing, bindings);
            this.strategy = loaded.strategy;
            this.groups.clear();
            this.groups.addAll(loaded.groups);
            log.debug("[AiChatConfig] loaded '{}' from Engine", name);
        } else {
            // 写入 Engine
            AiChatConfigEntity header = toHeader(name);
            engine.update(AiChatConfigEntity.class).saveOrUpdate(header);
            Long configId = header.getId();
            if (configId == null) {
                // 重新查询获取 ID
                AiChatConfigEntity saved = engine.query(AiChatConfigEntity.class)
                        .eq("name", name).one();
                configId = saved != null ? saved.getId() : null;
            }
            if (configId != null) {
                for (AiChatClientBindingEntity be : toBindings(configId)) {
                    engine.update(AiChatClientBindingEntity.class).saveOrUpdate(be);
                }
            }
            log.debug("[AiChatConfig] saved '{}' to Engine", name);
        }

        this.loaded = true;
        this.loadedName = name;
        return this;
    }

    /**
     * 从头实体 + 绑定列表反构建 AiChatConfig
     *
     * @param header   配置头
     * @param bindings 客户端绑定列表
     * @return AiChatConfig
     */
    public static AiChatConfig fromEntity(AiChatConfigEntity header, List<AiChatClientBindingEntity> bindings) {
        AiChatConfig config = new AiChatConfig();
        config.strategy = header.getStrategy();
        if (bindings == null || bindings.isEmpty()) {
            return config;
        }
        // 按 groupOrder 分组
        Map<Integer, List<AiChatClientBindingEntity>> groupMap =
                bindings.stream().collect(Collectors.groupingBy(
                        b -> b.getGroupOrder() != null ? b.getGroupOrder() : 0));
        groupMap.keySet().stream().sorted().forEach(order -> {
            List<AiChatClientBindingEntity> groupBindings = groupMap.get(order);
            if (groupBindings == null || groupBindings.isEmpty()) return;
            AiChatClientBindingEntity first = groupBindings.get(0);
            GroupBuilder gb = config.group(first.getGroupName())
                    .strategy(first.getGroupStrategy());
            if (first.getGroupCondition() != null) {
                gb.condition(first.getGroupCondition());
            }
            groupBindings.stream()
                    .sorted(Comparator.comparingInt(
                            b -> b.getClientOrder() != null ? b.getClientOrder() : 0))
                    .forEach(b -> {
                        config.currentGroup.add(b.getProvider(), b.getApiKey())
                                .model(b.getModel())
                                .weight(b.getWeight())
                                .baseUrl(b.getBaseUrl())
                                .temperature(b.getTemperature())
                                .maxTokens(b.getMaxTokens())
                                .system(b.getSystemPrompt())
                                .proxy(b.getProxy());
                    });
        });
        return config;
    }

    // ======================== 组 Builder ========================

    @Getter
    public static class GroupBuilder {
        private final AiChatConfig parent;
        /**
         * 名称
         */
        private String name;
        /**
         * 策略名称
         */
        private String strategy = "failover";
        private String condition;
        private final List<ClientBuilder> clients = new ArrayList<>();

        GroupBuilder(AiChatConfig parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        public GroupBuilder strategy(String strategy) {
            this.strategy = strategy;
            return this;
        }

        public GroupBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        /** 添加一个客户端配置 */
        public ClientBuilder add(String provider, String apiKey) {
            ClientBuilder cb = new ClientBuilder(this, provider, apiKey);
            clients.add(cb);
            return cb;
        }

        /** 返回上一级 */
        public AiChatConfig back() {
            return parent;
        }

        /** 开始一个新组（委托给父级） */
        public GroupBuilder group(String name) {
            return parent.group(name);
        }

        /** 构建完成 */
        public AiChatConfig build() {
            return parent.build();
        }

        AggregateChatClientSetting.GroupConfig toGroupConfig() {
            AggregateChatClientSetting.GroupConfig gc = new AggregateChatClientSetting.GroupConfig();
            gc.setName(name);
            gc.setStrategy(strategy);
            gc.setCondition(condition);
            List<AggregateChatClientSetting.ClientConfig> clientConfigs = new ArrayList<>();
            for (ClientBuilder cb : clients) {
                clientConfigs.add(cb.toClientConfig());
            }
            gc.setClients(clientConfigs);
            return gc;
        }
    }

    // ======================== 客户端 Builder ========================

    @Getter
    public static class ClientBuilder {
        private final GroupBuilder parent;
        /**
         * 提供方标识
         */
        private String provider;
        /**
         * API 密钥
         */
        private String apiKey;
        private String baseUrl;
        /**
         * 模型名称
         */
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String system;
        private String proxy;
        private int weight = 1;

        ClientBuilder(GroupBuilder parent, String provider, String apiKey) {
            this.parent = parent;
            this.provider = provider;
            this.apiKey = apiKey;
        }

        public ClientBuilder model(String model)            { this.model = model; return this; }
        public ClientBuilder weight(int weight)              { this.weight = weight; return this; }
        public ClientBuilder baseUrl(String baseUrl)         { this.baseUrl = baseUrl; return this; }
        public ClientBuilder temperature(Double temperature) { this.temperature = temperature; return this; }
        public ClientBuilder maxTokens(Integer maxTokens)    { this.maxTokens = maxTokens; return this; }
        public ClientBuilder system(String system)           { this.system = system; return this; }
        public ClientBuilder proxy(String proxy)             { this.proxy = proxy; return this; }

        /** 返回上一级 Group */
        public GroupBuilder back() {
            return parent;
        }

        /** 在当前组继续添加客户端 */
        public ClientBuilder add(String provider, String apiKey) {
            return parent.add(provider, apiKey);
        }

        /** 开始一个新组 */
        public GroupBuilder group(String name) {
            return parent.group(name);
        }

        /** 构建完成 */
        public AiChatConfig build() {
            return parent.build();
        }

        AggregateChatClientSetting.ClientConfig toClientConfig() {
            AggregateChatClientSetting.ClientConfig cc = new AggregateChatClientSetting.ClientConfig();
            cc.setProvider(provider);
            cc.setApiKey(apiKey);
            cc.setBaseUrl(baseUrl);
            cc.setModel(model);
            cc.setTemperature(temperature);
            cc.setMaxTokens(maxTokens);
            cc.setSystem(system);
            cc.setProxy(proxy);
            cc.setWeight(weight);
            return cc;
        }
    }
}