package com.chua.common.support.ai.chat.config;

import com.chua.common.support.ai.chat.aggregate.AggregateChatClient;
import com.chua.common.support.ai.chat.aggregate.AggregateChatClientSetting;
import com.chua.common.support.ai.context.ContextCompressionConfig;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 对话配置链式 Builder — 以 fluent API 替代 JSON/YAML 配置。
 *
 * <p>通过链式调用构建 {@link AggregateChatClientSetting}，再通过 Engine ORM 持久化。</p>
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
@Slf4j
@Getter
public class AiChatConfig {

    /**
     * 默认全局策略：hybrid（混合调度）
     */
    private static final String DEFAULT_STRATEGY = "hybrid";

    /**
     * 默认组策略：failover（故障转移）
     */
    private static final String DEFAULT_GROUP_STRATEGY = "failover";

    /**
     * 客户端默认权重
     */
    private static final int DEFAULT_WEIGHT = 1;

    /**
     * 排序字段：组内顺序
     */
    private static final String FIELD_GROUP_ORDER = "groupOrder";

    /**
     * 排序字段：客户端顺序
     */
    private static final String FIELD_CLIENT_ORDER = "clientOrder";

    /**
     * 排序表达式：组顺序升序 + 客户端顺序升序
     */
    private static final String ORDER_BY_GROUP_CLIENT = "groupOrder, clientOrder";

    /**
     * 数据库字段名：name
     */
    private static final String FIELD_NAME = "name";

    /**
     * 数据库字段名：configId
     */
    private static final String FIELD_CONFIG_ID = "configId";

    /**
     * 全局策略名称
     */
    private String strategy = DEFAULT_STRATEGY;

    /**
     * 上下文压缩配置
     */
    private ContextCompressionConfig compression;

    /**
     * 技能目录路径列表
     */
    private List<String> skillPaths;

    /**
     * 组配置列表
     */
    private final List<GroupBuilder> groups = CollectionUtils.newArrayList();

    /**
     * 当前 GroupBuilder（用于 add() 时直接添加到当前组）
     */
    private transient GroupBuilder currentGroup;

    /**
     * 是否已从 Engine 加载/写入
     */
    private transient volatile boolean loaded = false;

    /**
     * 加载时使用的名称
     */
    private transient String loadedName;

    // ======================== 顶级 API ========================

    /**
     * 创建一个新的配置实例。
     *
     * @return 新的 AiChatConfig
     */
    public static AiChatConfig configure() {
        return new AiChatConfig();
    }

    /**
     * 设置全局策略。
     *
     * @param strategy 策略名称
     * @return 自身（支持链式）
     */
    public AiChatConfig strategy(String strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
     * 设置上下文压缩配置。
     *
     * @param compression 压缩配置
     * @return 自身（支持链式）
     */
    public AiChatConfig compression(ContextCompressionConfig compression) {
        this.compression = compression;
        return this;
    }

    /**
     * 设置技能目录路径列表。
     *
     * @param skillPaths 技能路径列表
     * @return 自身（支持链式）
     */
    public AiChatConfig skillPaths(List<String> skillPaths) {
        this.skillPaths = skillPaths;
        return this;
    }

    /**
     * 开始一个新组。
     *
     * @param name 组名
     * @return 新组的 GroupBuilder
     */
    public GroupBuilder group(String name) {
        currentGroup = new GroupBuilder(this, name);
        groups.add(currentGroup);
        return currentGroup;
    }

    /**
     * 构建完成，返回自身（可链式调 toSetting()）。
     *
     * @return 自身
     */
    public AiChatConfig build() {
        return this;
    }

    /**
     * 转换为 AggregateChatClientSetting。
     *
     * @return 组装后的设置对象
     */
    public AggregateChatClientSetting toSetting() {
        AggregateChatClientSetting setting = new AggregateChatClientSetting();
        setting.setStrategy(strategy);
        setting.setCompression(compression);
        setting.setSkillPaths(skillPaths);

        List<AggregateChatClientSetting.GroupConfig> groupConfigs = CollectionUtils.newArrayList();
        for (GroupBuilder gb : groups) {
            groupConfigs.add(gb.toGroupConfig());
        }
        setting.setGroups(groupConfigs);
        return setting;
    }

    /**
     * 转换为配置头实体。
     *
     * @param name 配置名称
     * @return 配置头实体
     */
    public AiChatConfigEntity toHeader(String name) {
        AiChatConfigEntity header = new AiChatConfigEntity();
        header.setName(name);
        header.setStrategy(strategy);
        header.setMonitor(true);
        header.setUpdatedAt(System.currentTimeMillis());
        return header;
    }

    /**
     * 转换为客户端绑定实体列表。
     *
     * @param configId 配置 ID
     * @return 客户端绑定实体列表
     */
    public List<AiChatClientBindingEntity> toBindings(Long configId) {
        List<AiChatClientBindingEntity> result = CollectionUtils.newArrayList();
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
     * 后续调用（已加载）→ 直接跳过，无副作用。</p>
     *
     * @param engine Engine 实例
     * @param name   配置名称
     * @return 自身（支持链式）
     */
    public AiChatConfig loadFromEngine(Engine engine, String name) {
        if (engine == null || StringUtils.isBlank(name)) {
            return this;
        }
        if (loaded && name.equals(loadedName)) {
            return this;
        }

        AiChatConfigEntity existing = engine.query(AiChatConfigEntity.class)
                .eq(FIELD_NAME, name)
                .one();
        if (existing != null) {
            // 从 Engine 加载
            List<AiChatClientBindingEntity> bindings = engine.query(AiChatClientBindingEntity.class)
                    .eq(FIELD_CONFIG_ID, existing.getId())
                    .orderByAsc(ORDER_BY_GROUP_CLIENT)
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
                        .eq(FIELD_NAME, name)
                        .one();
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
     * 从头实体 + 绑定列表反构建 AiChatConfig。
     *
     * @param header   配置头
     * @param bindings 客户端绑定列表
     * @return AiChatConfig
     */
    public static AiChatConfig fromEntity(AiChatConfigEntity header, List<AiChatClientBindingEntity> bindings) {
        AiChatConfig config = new AiChatConfig();
        config.strategy = header.getStrategy();
        if (CollectionUtils.isEmpty(bindings)) {
            return config;
        }
        // 按 groupOrder 分组
        Map<Integer, List<AiChatClientBindingEntity>> groupMap =
                bindings.stream().collect(Collectors.groupingBy(
                        b -> b.getGroupOrder() != null ? b.getGroupOrder() : 0));
        groupMap.keySet().stream().sorted().forEach(order -> {
            List<AiChatClientBindingEntity> groupBindings = groupMap.get(order);
            if (CollectionUtils.isEmpty(groupBindings)) {
                return;
            }
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

    /**
     * 组构建器，用于构建同组内多个客户端配置。
     *
     * @since 4.0.0.42
     */
    @Getter
    public static class GroupBuilder {

        /**
         * 父级 AiChatConfig
         */
        private final AiChatConfig parent;

        /**
         * 组名称
         */
        private String name;

        /**
         * 组策略名称
         */
        private String strategy = DEFAULT_GROUP_STRATEGY;

        /**
         * 触发条件
         */
        private String condition;

        /**
         * 客户端列表
         */
        private final List<ClientBuilder> clients = CollectionUtils.newArrayList();

        /**
         * 构造一个 GroupBuilder。
         *
         * @param parent 父级配置
         * @param name   组名
         */
        GroupBuilder(AiChatConfig parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        /**
         * 设置组策略。
         *
         * @param strategy 策略名称
         * @return 自身（支持链式）
         */
        public GroupBuilder strategy(String strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * 设置触发条件。
         *
         * @param condition 触发条件表达式
         * @return 自身（支持链式）
         */
        public GroupBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        /**
         * 添加一个客户端配置。
         *
         * @param provider 提供方标识
         * @param apiKey   API 密钥
         * @return 新客户端的 ClientBuilder
         */
        public ClientBuilder add(String provider, String apiKey) {
            ClientBuilder cb = new ClientBuilder(this, provider, apiKey);
            clients.add(cb);
            return cb;
        }

        /**
         * 返回上一级。
         *
         * @return 父级 AiChatConfig
         */
        public AiChatConfig back() {
            return parent;
        }

        /**
         * 开始一个新组（委托给父级）。
         *
         * @param name 新组名称
         * @return 新组的 GroupBuilder
         */
        public GroupBuilder group(String name) {
            return parent.group(name);
        }

        /**
         * 构建完成。
         *
         * @return 父级 AiChatConfig
         */
        public AiChatConfig build() {
            return parent.build();
        }

        /**
         * 转换为 AggregateChatClientSetting.GroupConfig。
         *
         * @return 组配置对象
         */
        AggregateChatClientSetting.GroupConfig toGroupConfig() {
            AggregateChatClientSetting.GroupConfig gc = new AggregateChatClientSetting.GroupConfig();
            gc.setName(name);
            gc.setStrategy(strategy);
            gc.setCondition(condition);
            List<AggregateChatClientSetting.ClientConfig> clientConfigs = CollectionUtils.newArrayList();
            for (ClientBuilder cb : clients) {
                clientConfigs.add(cb.toClientConfig());
            }
            gc.setClients(clientConfigs);
            return gc;
        }
    }

    // ======================== 客户端 Builder ========================

    /**
     * 客户端构建器，用于构建单个客户端的配置项。
     *
     * @since 4.0.0.42
     */
    @Getter
    public static class ClientBuilder {

        /**
         * 父级 GroupBuilder
         */
        private final GroupBuilder parent;

        /**
         * 提供方标识
         */
        private String provider;

        /**
         * API 密钥
         */
        private String apiKey;

        /**
         * Base URL
         */
        private String baseUrl;

        /**
         * 模型名称
         */
        private String model;

        /**
         * 温度参数
         */
        private Double temperature;

        /**
         * 最大 token 数
         */
        private Integer maxTokens;

        /**
         * 系统提示词
         */
        private String system;

        /**
         * 代理配置
         */
        private String proxy;

        /**
         * 权重
         */
        private int weight = DEFAULT_WEIGHT;

        /**
         * 构造一个 ClientBuilder。
         *
         * @param parent   父级 GroupBuilder
         * @param provider 提供方标识
         * @param apiKey   API 密钥
         */
        ClientBuilder(GroupBuilder parent, String provider, String apiKey) {
            this.parent = parent;
            this.provider = provider;
            this.apiKey = apiKey;
        }

        /**
         * 设置模型。
         *
         * @param model 模型名称
         * @return 自身（支持链式）
         */
        public ClientBuilder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 设置权重。
         *
         * @param weight 权重值
         * @return 自身（支持链式）
         */
        public ClientBuilder weight(int weight) {
            this.weight = weight;
            return this;
        }

        /**
         * 设置 Base URL。
         *
         * @param baseUrl Base URL
         * @return 自身（支持链式）
         */
        public ClientBuilder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 设置温度参数。
         *
         * @param temperature 温度参数
         * @return 自身（支持链式）
         */
        public ClientBuilder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 设置最大 token 数。
         *
         * @param maxTokens 最大 token 数
         * @return 自身（支持链式）
         */
        public ClientBuilder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 设置系统提示词。
         *
         * @param system 系统提示词
         * @return 自身（支持链式）
         */
        public ClientBuilder system(String system) {
            this.system = system;
            return this;
        }

        /**
         * 设置代理。
         *
         * @param proxy 代理配置
         * @return 自身（支持链式）
         */
        public ClientBuilder proxy(String proxy) {
            this.proxy = proxy;
            return this;
        }

        /**
         * 返回上一级 Group。
         *
         * @return 父级 GroupBuilder
         */
        public GroupBuilder back() {
            return parent;
        }

        /**
         * 在当前组继续添加客户端。
         *
         * @param provider 提供方标识
         * @param apiKey   API 密钥
         * @return 新客户端的 ClientBuilder
         */
        public ClientBuilder add(String provider, String apiKey) {
            return parent.add(provider, apiKey);
        }

        /**
         * 开始一个新组。
         *
         * @param name 新组名称
         * @return 新组的 GroupBuilder
         */
        public GroupBuilder group(String name) {
            return parent.group(name);
        }

        /**
         * 构建完成。
         *
         * @return 顶级 AiChatConfig
         */
        public AiChatConfig build() {
            return parent.build();
        }

        /**
         * 转换为 AggregateChatClientSetting.ClientConfig。
         *
         * @return 客户端配置对象
         */
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
