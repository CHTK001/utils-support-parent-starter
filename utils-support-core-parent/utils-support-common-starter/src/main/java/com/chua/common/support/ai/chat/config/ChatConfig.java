package com.chua.common.support.ai.chat.config;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.lang.datasource.engine.Engine;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullUnmarked;

/**
 * 单个 ChatClient 配置的链式 Builder — 以 fluent API 替代 JSON/@Builder 配置。
 *
 * <p>构建 {@link ChatClientSetting} 并通过 Engine ORM 持久化，所有字段均为属性，无需 JSON。
 *
 * <p>使用示例：
 * <pre>{@code
 *   // 链式构建
 *   ChatConfig config = ChatConfig.configure()
 *       .provider("openai").apiKey("sk-xxx")
 *       .model("gpt-4").temperature(0.7)
 *       .system("你是一名助手")
 *       .build();
 *
 *   // 创建 ChatClient
 *   ChatClient client = config.newChatClient();
 *   String answer = client.chatSync("你好");
 *
 *   // 通过 Engine 持久化（属性字段）
 *   engine.update(ChatConfigEntity.class).saveOrUpdate(config.toEntity("my-openai"));
 *
 *   // 从 Engine 加载
 *   ChatConfigEntity entity = engine.query(ChatConfigEntity.class)
 *       .eq("name", "my-openai").one();
 *   ChatClient client = entity.toChatClient();
 *
 *   // 按字段查询
 *   List<ChatConfigEntity> openaiConfigs = engine.query(ChatConfigEntity.class)
 *       .eq("provider", "openai").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class ChatConfig {

    /**
     * 提供方标识
     */
    private String provider;
    /**
     * API 密钥
     */
    private String apiKey;
    /**
     * 应用密钥
     */
    private String appSecret;
    private String baseUrl;
    /**
     * 模型名称
     */
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private String system;
    private String proxy;

    /** 是否已从 Engine 加载/写入 */
    private transient volatile boolean loaded = false;
    /** 加载时使用的名称 */
    private transient String loadedName;

    // ======================== 顶级 API ========================

    /** 创建链式 Builder */
    public static ChatConfig configure() {
        return new ChatConfig();
    }

    public ChatConfig provider(String provider) {
        this.provider = provider;
        return this;
    }

    public ChatConfig apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    public ChatConfig appSecret(String appSecret) {
        this.appSecret = appSecret;
        return this;
    }

    public ChatConfig baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public ChatConfig model(String model) {
        this.model = model;
        return this;
    }

    public ChatConfig temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public ChatConfig maxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    public ChatConfig topP(Double topP) {
        this.topP = topP;
        return this;
    }

    public ChatConfig system(String system) {
        this.system = system;
        return this;
    }

    public ChatConfig proxy(String proxy) {
        this.proxy = proxy;
        return this;
    }

    // ======================== 构建 ========================

    /**
     * 构建完成，返回自身
     *
     * @return 当前配置实例
     */
    public ChatConfig build() {
        return this;
    }

    /**
     * 转换为 {@link ChatClientSetting}，用于 SPI 创建 ChatClient
     *
     * @return ChatClientSetting
     */
    public ChatClientSetting toSetting() {
        return ChatClientSetting.builder()
                .provider(provider)
                .appKey(apiKey)
                .appSecret(appSecret)
                .baseUrl(baseUrl)
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .topP(topP)
                .system(system)
                .proxy(proxy)
                .build();
    }

    /**
     * 直接创建 {@link ChatClient}（等效于 {@code ChatClient.create(toSetting())}）
     *
     * @return ChatClient 实例
     */
    public ChatClient newChatClient() {
        return ChatClient.create(toSetting());
    }

    // ======================== 持久化 ========================

    /**
     * 转换为持久化实体
     *
     * @param name 配置名称（如 "production"、"staging"）
     * @return ChatConfigEntity
     */
    public ChatConfigEntity toEntity(String name) {
        return ChatConfigEntity.from(this, name);
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
     * @param name   配置名称（如 "production"）
     * @return 自身（支持链式）
     */
    public ChatConfig loadFromEngine(Engine engine, String name) {
        if (engine == null || name == null || name.isBlank()) return this;
        if (loaded && name.equals(loadedName)) return this;

        ChatConfigEntity existing = engine.query(ChatConfigEntity.class)
                .eq("name", name).one();
        if (existing != null) {
            // 从 Engine 加载 → 覆盖当前字段
            this.provider = existing.getProvider();
            this.apiKey = existing.getAppKey();
            this.appSecret = existing.getAppSecret();
            this.baseUrl = existing.getBaseUrl();
            this.model = existing.getModel();
            this.temperature = existing.getTemperature();
            this.maxTokens = existing.getMaxTokens();
            this.topP = existing.getTopP();
            this.system = existing.getSystem();
            this.proxy = existing.getProxy();
            log.debug("[ChatConfig] loaded '{}' from Engine", name);
        } else {
            // 写入 Engine
            ChatConfigEntity entity = toEntity(name);
            engine.update(ChatConfigEntity.class).saveOrUpdate(entity);
            log.debug("[ChatConfig] saved '{}' to Engine", name);
        }

        this.loaded = true;
        this.loadedName = name;
        return this;
    }

    /**
     * 从持久化实体反构建
     *
     * @param entity 持久化实体
     * @return ChatConfig
     */
    public static ChatConfig fromEntity(ChatConfigEntity entity) {
        if (entity == null) return null;
        return ChatConfig.configure()
                .provider(entity.getProvider())
                .apiKey(entity.getAppKey())
                .appSecret(entity.getAppSecret())
                .baseUrl(entity.getBaseUrl())
                .model(entity.getModel())
                .temperature(entity.getTemperature())
                .maxTokens(entity.getMaxTokens())
                .topP(entity.getTopP())
                .system(entity.getSystem())
                .proxy(entity.getProxy())
                .build();
    }
}