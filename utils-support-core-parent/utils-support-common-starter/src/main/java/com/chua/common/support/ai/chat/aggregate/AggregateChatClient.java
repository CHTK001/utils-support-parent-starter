package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.aggregate.monitor.UsageStats;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import com.chua.common.support.ai.chat.aggregate.strategy.HybridStrategy;
import com.chua.common.support.ai.chat.protocol.AiTokenProvider;
import com.chua.common.support.ai.chat.protocol.AiProtocolServerFilter;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.ai.chat.aggregate.ModelHealthChecker;
import com.chua.common.support.ai.chat.usage.AiUsageRecord;
import com.chua.common.support.ai.context.ContextCompressor;
import com.chua.common.support.ai.skill.DefaultSkillManager;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillPrompt;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.file.FileSystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 聚合 AI 对话客户端。
 *
 * <p>实现 {@link ChatClient} 接口，通过 JSON 配置管理多个 AI Provider。
 * 路由策略由 {@link RouterStrategy#select(List, String)} 决定选择哪个客户端，
 * 故障转移由 {@link FailoverTemplate} 统一处理，用量通过 {@link AiUsage} 追踪。
 * 对调用方完全透明，与普通 ChatClient 用法一致。
 *
 * <p>SPI 注册为 {@code "aggregate"}：
 * <pre>{@code
 *   ChatClient client = ChatClient.create("aggregate", jsonConfig);
 *   String answer = client.chatSync("你好");
 * }</pre>
 *
 * <p>支持的策略：{@code failover}, {@code round_robin}, {@code weighted},
 * {@code cost}, {'latency'}, {@code hybrid}。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("aggregate")
public class AggregateChatClient implements ChatClient {

    /**
     * 聚合配置，包含路由策略、多组配置、客户端列表等
     */
    private final AggregateChatClientSetting config;

    /**
     * 路由策略实例，负责从候选客户端中选择目标
     */
    private final RouterStrategy router;

    /**
     * 所有已创建的客户端实例列表（并发安全，支持运行时增删实现热更新）
     */
    private final CopyOnWriteArrayList<RouterStrategy.WeightedClient> allClients;

    /**
     * 模型健康检查器
     */
    private final ModelHealthChecker healthChecker;

    /**
     * 健康过滤器：true 表示客户端可用
     */
    private final Predicate<RouterStrategy.WeightedClient> healthFilter;

    /**
     * 是否启用自动切换
     */
    private final boolean autoSwitchEnabled;

    /**
     * 内存中的用量记录列表
     */
    private final List<AiUsage> usageRecords = new CopyOnWriteArrayList<>();

    /**
     * 待完成的异步用量持久化任务列表
     */
    private final List<CompletableFuture<?>> pendingFutures = new CopyOnWriteArrayList<>();

    /**
     * 数据引擎，用于异步持久化用量记录
     */
    private Engine engine;

    /**
     * 用量收集回调函数
     */
    private final Consumer<AiUsage> usageCollector;

    /**
     * 上下文压缩器，用于压缩长对话
     */
    private final ContextCompressor compressor;

    /**
     * Skill 管理器，用于注入 Skill 到 system prompt
     */
    private final SkillManager skillManager;

    /**
     * 令牌提供者，用于 RESTful 接口的 Bearer Token 认证
     */
    private AiTokenProvider tokenProvider;

    /**
     * 当前线程的 token 分组（用于模型分组路由）
     */
    private static final ThreadLocal<String> CURRENT_TOKEN_GROUP = new ThreadLocal<>();

    /**
     * 客户端是否已关闭
     */
    private volatile boolean closed = false;

    {
        this.usageCollector = usage -> {
            if (usage == null) {
                return;
            }
            usageRecords.add(usage);
            if (engine != null) {
                CompletableFuture<AiUsageRecord> future = AiUsageRecord.from(usage).asyncSave(engine);
                pendingFutures.add(future);
                future.whenComplete((r, t) -> {
                    if (pendingFutures.remove(future) && t != null) {
                        log.warn("[Aggregate] async persist usage failed: {}", t.getMessage());
                    }
                });
            } else {
            }
        };
    }

    // ======================== 构造方法 ========================

    /**
     * 通过 ChatClientSetting 构造。
     * 从 setting 的 appKey 或 model 字段读取 JSON 配置。
     *
     * @param setting 客户端配置
     */
    public AggregateChatClient(ChatClientSetting setting) {
        this(parseConfig(setting), null);
    }

    /**
     * 通过 ChatClientSetting 和 Engine 构造。
     *
     * @param setting 客户端配置
     * @param engine  数据引擎
     */
    public AggregateChatClient(ChatClientSetting setting, Engine engine) {
        this(parseConfig(setting), engine);
    }

    /**
     * 通过 JSON 配置字符串构造。
     *
     * @param jsonConfig JSON 配置字符串
     */
    public AggregateChatClient(String jsonConfig) {
        this(jsonConfig, null);
    }

    /**
     * 通过 JSON 配置字符串和 Engine 构造。
     *
     * @param jsonConfig JSON 配置字符串
     * @param engine     数据引擎
     */
    public AggregateChatClient(String jsonConfig, Engine engine) {
        this(Json.fromJson(jsonConfig, AggregateChatClientSetting.class), engine);
    }

    /**
     * 通过 AggregateChatClientSetting 构造。
     *
     * @param config 聚合配置
     */
    public AggregateChatClient(AggregateChatClientSetting config) {
        this(config, null);
    }

    /**
     * 通过 AggregateChatClientSetting 和 Engine 构造。
     *
     * @param config 聚合配置
     * @param engine 数据引擎
     */
    public AggregateChatClient(AggregateChatClientSetting config, Engine engine) {
        this.config = config;
        this.engine = engine;
        this.compressor = ContextCompressor.create(config.getCompression(), this);
        this.skillManager = buildSkillManager(config, engine);
        AllParsed parsed = parseGroups(config);
        this.allClients = new CopyOnWriteArrayList<>(parsed.allClients);

        boolean autoSwitchEnabled = config.isAutoSwitchOnRateLimit() || config.isAutoSwitchOnQuotaExhausted();
        this.autoSwitchEnabled = autoSwitchEnabled;

        if (config.isEnableHealthCheck() && autoSwitchEnabled) {
            this.healthChecker = new ModelHealthChecker(config.getHealthCheckIntervalMs(), this::checkClientHealth);
            this.healthFilter = wc -> {
                if (healthChecker == null) {
                    return true;
                }
                ModelHealth health = healthChecker.getHealth(wc.client());
                if (health == null) {
                    return true;
                }
                if (config.isAutoSwitchOnRateLimit() && health.isRateLimited()) {
                    return false;
                }
                if (config.isAutoSwitchOnQuotaExhausted() && health.isQuotaExhausted()) {
                    return false;
                }
                return true;
            };
            for (RouterStrategy.WeightedClient wc : allClients) {
                healthChecker.register(wc.client(), wc.provider(), wc.model());
            }
            healthChecker.start();
        } else {
            this.healthChecker = null;
            this.healthFilter = wc -> true;
        }

        this.router = buildRouter(config.getStrategy(), parsed, healthFilter);
    }

    // ======================== 构造辅助方法 ========================

    /**
     * 构建 Skill 管理器。
     * 从配置中的 skillPaths 加载 YAML/JSON 格式的 Skill 定义。
     *
     * @param config 聚合配置
     * @param engine 数据引擎
     * @return Skill 管理器实例，无技能路径返回 null
     */
    private static SkillManager buildSkillManager(AggregateChatClientSetting config, Engine engine) {
        List<String> paths = config.getSkillPaths();
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        SkillManager manager = new DefaultSkillManager();
        for (String path : paths) {
            try {
                loadSkillsFromPath(manager, path);
            } catch (Exception e) {
                log.warn("[Aggregate] load skills from {} failed: {}", path, e.getMessage());
            }
        }
        return manager;
    }

    /**
     * 从指定路径加载 Skill 定义。
     * 支持 YAML（.yaml/.yml）和 JSON 格式文件。
     *
     * @param manager 目标 Skill 管理器
     * @param path    技能文件目录路径
     */
    private static void loadSkillsFromPath(SkillManager manager, String path) {
        Path root = Path.of(path);
        if (!java.nio.file.Files.exists(root) || !java.nio.file.Files.isDirectory(root)) {
            log.warn("[Aggregate] skill path not found or not directory: {}", path);
            return;
        }
        try (Stream<Path> stream = java.nio.file.Files.walk(root)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
                    })
                    .forEach(p -> {
                        try {
                            Map<String, Object> data = FileSystem.create("yaml").read(p.toFile()).toMap();
                            SkillDefinition definition = Json.getMapper().convertValue(data, SkillDefinition.class);
                            if (definition != null && definition.getName() != null) {
                                manager.register(definition);
                                log.debug("[Aggregate] loaded skill {} from {}", definition.getName(), p);
                            }
                        } catch (Exception e) {
                            log.warn("[Aggregate] parse skill file {} failed: {}", p, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("[Aggregate] walk skill path {} failed: {}", path, e.getMessage());
        }
    }

    // ======================== ChatClient 接口 ========================

    /**
     * 同步对话，返回响应文本。
     *
     * @param prompt 用户输入
     * @return 响应文本
     * @throws RuntimeException 全部客户端失败时抛出
     */
    @Override
    public String chatSync(String prompt) {
        checkClosed();
        try {
            String prepared = preparePrompt(prompt);
            List<RouterStrategy.WeightedClient> candidates = filterHealthy(allClients);
            return router.executeSync(candidates.isEmpty() ? allClients : candidates, prepared, usageCollector);
        } catch (Exception e) {
            throw new RuntimeException("AggregateChatClient failed: " + e.getMessage(), e);
        } finally {
            clearTokenGroup();
        }
    }

    /**
     * 流式对话，通过 Consumer 回调接收响应。
     *
     * @param prompt   用户输入
     * @param consumer 响应回调
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, e -> {
            throw new RuntimeException(e);
        });
    }

    /**
     * 流式对话，支持完成和错误回调。
     *
     * @param prompt     用户输入
     * @param consumer   响应回调
     * @param onComplete 完成回调
     * @param onError    错误回调
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        checkClosed();
        try {
            String prepared = preparePrompt(prompt);
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
            List<RouterStrategy.WeightedClient> candidates = filterHealthy(allClients);
            router.executeStream(candidates.isEmpty() ? allClients : candidates, prepared, consumer);
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.STOP).build());
            onComplete.run();
        } catch (Exception e) {
            log.error("[AggregateChatClient] stream failed: {}", e.getMessage(), e);
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR).errorMessage(e.getMessage()).build());
            onError.accept(e);
        } finally {
            clearTokenGroup();
        }
    }

    /**
     * 准备提示词，执行上下文压缩和系统提示词注入。
     *
     * @param prompt 原始用户输入
     * @return 处理后的提示词
     */
    private String preparePrompt(String prompt) {
        if (prompt == null) {
            return null;
        }
        String compressed = compressor != null && compressor.isEnabled()
                ? compressor.compressPrompt(prompt)
                : prompt;
        if (compressed != null) {
            return compressed;
        }
        return prompt;
    }

    /**
     * 过滤出健康的客户端列表
     */
    private List<RouterStrategy.WeightedClient> filterHealthy(List<RouterStrategy.WeightedClient> clients) {
        if (!autoSwitchEnabled) {
            return clients;
        }
        List<RouterStrategy.WeightedClient> result = new ArrayList<>(clients.size());
        for (RouterStrategy.WeightedClient wc : clients) {
            if (healthFilter.test(wc)) {
                result.add(wc);
            }
        }
        return result;
    }

    /**
     * 检查客户端健康状态（用于定时健康检查）
     */
    private ModelHealthChecker.ModelHealthCheckResult checkClientHealth(ChatClient client) {
        try {
            client.chatSync("ping");
            return ModelHealthChecker.ModelHealthCheckResult.healthy();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("rate limit") || msg.contains("429")) {
                return ModelHealthChecker.ModelHealthCheckResult.rateLimited(e.getMessage());
            }
            if (msg.contains("quota") || msg.contains("insufficient") || msg.contains("balance")) {
                return ModelHealthChecker.ModelHealthCheckResult.quotaExhausted(e.getMessage());
            }
            return ModelHealthChecker.ModelHealthCheckResult.error(e.getMessage());
        }
    }

    /**
     * 同步对话并返回完整响应（含用量信息）。
    @Override
    /** ChatSyncWithResponse */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder().text(text).usage(aggregateUsage()).build();
    }

    /**
     * 异步对话，返回 CompletableFuture。
     *
     * @param prompt 用户输入
     * @return 异步响应
     */
    @Override
    public CompletableFuture<ChatSyncResponse> chatAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> chatSyncWithResponse(prompt));
    }

    /**
     * 获取所有已配置客户端的模型定义列表（去重）。
     *
     * @return 模型定义列表
     */
    @Override
    public List<ModelDefinition> models() {
        checkClosed();
        return allClients.stream()
                .flatMap(wc -> {
                    try {
                        return wc.client().models().stream();
                    } catch (Exception e) {
                        log.debug("models() from {} failed: {}", wc.provider(), e.getMessage());
                        return Stream.empty();
                    }
                })
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已配置客户端的模型定价列表（去重）。
     *
     * @return 模型定价列表
     */
    @Override
    public List<ModelDefinition> modelPricing() {
        checkClosed();
        return allClients.stream()
                .flatMap(wc -> {
                    try {
                        return wc.client().modelPricing().stream();
                    } catch (Exception e) {
                        log.debug("modelPricing() from {} failed: {}", wc.provider(), e.getMessage());
                        return Stream.empty();
                    }
                })
                .distinct()
                .collect(Collectors.toList());
    }

    // ======================== 异步持久化控制 ========================

    /**
     * 等待所有异步用量写入完成。
     * <p>
     * 在 {@link #close()} 前调用可确保所有待写入的用量记录已持久化。
     */
    public void flush() {
        List<CompletableFuture<?>> pending = List.copyOf(pendingFutures);
        if (pending.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .join();
        } catch (Exception e) {
            log.warn("[Aggregate] flush usage persistence failed: {}", e.getMessage());
        }
    }

    /**
     * 从外部 SPI 同步用量到当前 Engine。
     * <p>
     * 将外部数据源（如 UsageParser 解析的本地工具用量）批量写入 Engine 持久化表，
     * 同时合并到内存中的 usageRecords，供 stats/aggregateUsage 查询。</p>
     * <p>
     * 使用示例：
     * <pre>{@code
     *   // 从 UsageParser 同步
     *   UsageParser parser = ServiceProvider.of(UsageParser.class).getExtension("opencode");
     *   client.syncUsage(parser.parseAll());
     *
     *   // 从任意来源同步
     *   client.syncUsage(myCustomUsageList);
     * }</pre>
     *
     * @param externalUsage 外部来源的用量数据列表
     */
    public void syncUsage(List<AiUsage> externalUsage) {
        if (externalUsage == null || externalUsage.isEmpty()) {
            return;
        }
        if (engine == null) {
            log.debug("[Aggregate] Engine 不可用，仅合并到内存");
            usageRecords.addAll(externalUsage);
            return;
        }
        List<AiUsage> enriched = externalUsage.stream()
                .filter(u -> u != null)
                .map(u -> {
                    if (u.getProvider() == null) {
                        u.setProvider("external-sync");
                    }
                    if (u.getRequestId() == null) {
                        u.setRequestId("sync-" + System.nanoTime());
                    }
                    return u;
                })
                .collect(Collectors.toList());
        usageRecords.addAll(enriched);
        for (AiUsage usage : enriched) {
            CompletableFuture<AiUsageRecord> future = AiUsageRecord.from(usage).asyncSave(engine);
            pendingFutures.add(future);
            future.whenComplete((r, t) -> {
                if (pendingFutures.remove(future) && t != null) {
                    log.warn("[Aggregate] sync persist failed: {}", t.getMessage());
                }
            });
        }
        log.info("[Aggregate] 从外部同步 {} 条用量到 Engine", enriched.size());
    }

    /**
     * 关闭客户端，释放所有资源。
     * 先 flush 异步持久化任务，再逐个关闭各客户端。
     */
    @Override
    public void close() {
        closed = true;
        if (healthChecker != null) {
            healthChecker.stop();
        }
        flush();
        for (RouterStrategy.WeightedClient wc : allClients) {
            try {
                wc.client().close();
            } catch (Exception e) {
                log.debug("close {} failed: {}", wc.provider(), e.getMessage());
            }
        }
    }

    // ======================== 令牌提供者 ========================

    /**
     * 设置令牌提供者，用于 RESTful 接口的 Bearer Token 认证。
     *
     * @param tokenProvider 令牌提供者实例
     */
    public void setTokenProvider(AiTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        log.info("[Aggregate] 令牌提供者已设置: {}", tokenProvider.getClass().getSimpleName());
    }

    /**
     * 获取令牌提供者。
     *
     * @return AiTokenProvider，未设置返回 null
     */
    public AiTokenProvider getTokenProvider() {
        return tokenProvider;
    }

    // ======================== 协议服务绑定 ========================

    /**
     * 协议服务过滤器实例（OpenAI/Claude/Gemini 三协议路由）
     */
    private AiProtocolServerFilter protocolServerFilter;

    /**
     * 将聚合 ChatClient 绑定到协议 Server，自动挂载三协议路由与 Token 前置拦截。
     *
     * <p>绑定后，通过 {@code Server} 即可对外提供 OpenAI / Claude / Gemini 三类协议端点，
     * 内部统一走聚合路由。支持运行时重复绑定（幂等）。</p>
     *
     * @param server 协议服务实例
     */
    public void bindServer(Server server) {
        if (server == null) {
            log.warn("[Aggregate] bindServer 忽略空 Server");
            return;
        }
        if (protocolServerFilter != null) {
            log.debug("[Aggregate] 已绑定过协议过滤器，跳过重复挂载");
            return;
        }
        AiProtocolServerFilter filter = new AiProtocolServerFilter(this);
        server.addFilter(filter);
        this.protocolServerFilter = filter;
        log.info("[Aggregate] 聚合 ChatClient 已绑定协议 Server: {} 三协议路由可用",
                server.getProtocolType());
    }

    /**
     * 解绑协议 Server（移除已挂载的协议过滤器）。
     *
     * @param server 协议服务实例
     */
    public void unbindServer(Server server) {
        if (server == null || protocolServerFilter == null) {
            return;
        }
        server.removeFilter(protocolServerFilter);
        this.protocolServerFilter = null;
        log.info("[Aggregate] 聚合 ChatClient 已解绑协议 Server");
    }



    /**
     * 动态添加一个底层 ChatClient 到聚合路由中。
     *
     * <p>适配数据库（厂商/模型表）变化后的热更新：新增厂商或模型时，
     * 无需重建聚合客户端，直接加入即可参与路由。</p>
     *
     * @param provider 提供商标识
     * @param model    模型名
     * @param weight   路由权重
     * @param client   底层 ChatClient 实例
     */
    public void addClient(String provider, String model, int weight, ChatClient client) {
        if (client == null) {
            log.warn("[Aggregate] addClient 忽略空客户端: provider={}", provider);
            return;
        }
        String modelName = model != null ? model : "default";
        allClients.add(new RouterStrategy.WeightedClient(provider, modelName, weight, client));
        if (healthChecker != null) {
            healthChecker.register(client, provider, modelName);
        }
        log.info("[Aggregate] 已添加聚合客户端: provider={}, model={}", provider, modelName);
    }

    /**
     * 按提供商标识动态移除底层 ChatClient，支持热更新。
     *
     * @param provider 提供商标识
     * @return 是否成功移除
     */
    public boolean removeClient(String provider) {
        boolean removed = allClients.removeIf(wc -> provider != null && provider.equals(wc.provider()));
        if (removed) {
            log.info("[Aggregate] 已移除聚合客户端: provider={}", provider);
        } else {
            log.debug("[Aggregate] 未找到可移除的聚合客户端: provider={}", provider);
        }
        return removed;
    }

    /**
     * 获取当前全部底层客户端的只读快照。
     *
     * @return 客户端列表快照
     */
    public List<RouterStrategy.WeightedClient> clients() {
        return List.copyOf(allClients);
    }

    /**
     * 获取当前底层客户端数量。
     *
     * @return 客户端数量
     */
    public int clientCount() {
        return allClients.size();
    }

    /**
     * 设置当前线程的 token 分组，用于模型分组路由。
     * <p>
     * 调用 {@link #chatSync} 或 {@link #chat} 前调用此方法，
     * 路由策略会根据 {@link AggregateChatClientSetting.GroupConfig#tokenGroups}
     * 过滤允许访问的模型组。</p>
     *
     * @param tokenGroup token 分组名称，null 表示不限制
     * @return 当前客户端实例，支持链式调用
     */
    public AggregateChatClient withTokenGroup(String tokenGroup) {
        if (tokenGroup != null) {
            CURRENT_TOKEN_GROUP.set(tokenGroup);
        } else {
            CURRENT_TOKEN_GROUP.remove();
        }
        return this;
    }

    /**
     * 获取当前线程的 token 分组。
     *
     * @return token 分组，未设置返回 null
     */
    public static String getCurrentTokenGroup() {
        return CURRENT_TOKEN_GROUP.get();
    }

    /**
     * 清除当前线程的 token 分组。
     */
    public static void clearTokenGroup() {
        CURRENT_TOKEN_GROUP.remove();
    }

    // ======================== 监控 API ========================

    /**
     * 获取用量统计摘要。
     *
     * @return 用量统计，包含总调用数、总 Token 数、平均延迟
     */
    public UsageStats stats() {
        List<AiUsage> records = List.copyOf(usageRecords);
        long total = records.size();
        long tokens = records.stream()
                .filter(u -> u.getTotalTokens() != null)
                .mapToLong(AiUsage::getTotalTokens)
                .sum();
        double avgLatency = records.stream()
                .filter(u -> u.getDurationMillis() != null)
                .mapToLong(AiUsage::getDurationMillis)
                .average().orElse(0);
        return UsageStats.builder()
                .totalCalls(total)
                .totalTokens(tokens)
                .avgLatencyMs(avgLatency)
                .build();
    }

    /**
     * 获取聚合后的用量信息。
     *
     * @return 聚合用量，无记录返回 null
     */
    public AiUsage aggregateUsage() {
        List<AiUsage> records = List.copyOf(usageRecords);
        if (records.isEmpty()) {
            return null;
        }
        return AiUsage.builder()
                .totalTokens(records.stream()
                        .filter(u -> u.getTotalTokens() != null)
                        .mapToInt(AiUsage::getTotalTokens).sum())
                .inputTokens(records.stream()
                        .filter(u -> u.getInputTokens() != null)
                        .mapToInt(AiUsage::getInputTokens).sum())
                .outputTokens(records.stream()
                        .filter(u -> u.getOutputTokens() != null)
                        .mapToInt(AiUsage::getOutputTokens).sum())
                .estimated(true)
                .build();
    }

    // ======================== 内部实现 ========================

    /**
     * 检查客户端是否已关闭。
     *
     * @throws IllegalStateException 如果已关闭
     */
    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("AggregateChatClient has been closed");
        }
    }

    /**
     * 从 ChatClientSetting 中解析 JSON 配置字符串。
     * 优先使用 appKey 字段，其次使用 model 字段。
     *
     * @param setting 客户端配置
     * @return JSON 配置字符串
     * @throws IllegalArgumentException 如果无法从 setting 中提取 JSON 配置
     */
    private static String parseConfig(ChatClientSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("setting must not be null");
        }
        String json = setting.getAppKey();
        if (json == null || json.isBlank()) {
            json = setting.getModel();
        }
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("AggregateChatClient needs JSON config in appKey");
        }
        return json;
    }

    /**
     * 构建路由策略。
     * 通过 ServiceProvider SPI 解析路由策略实现，
     * hybrid 策略使用分组路由，其他策略使用扁平路由。
     *
     * @param strategyName 策略名称
     * @param parsed       解析后的配置
     * @return 路由策略实例
     */
    private static RouterStrategy buildRouter(String strategyName, AllParsed parsed,
                                                Predicate<RouterStrategy.WeightedClient> healthFilter) {
        if ("hybrid".equalsIgnoreCase(strategyName)) {
            return ServiceProvider.of(RouterStrategy.class)
                    .getNewExtension("hybrid", parsed.groupRouters, healthFilter);
        }
        return createFlatStrategy(strategyName);
    }

    /**
     * 创建扁平路由策略实例。
     * 通过 SPI 扩展名解析对应实现，未知扩展名回退到默认策略 failover。
     *
     * @param name 策略名称，null 时默认使用 failover
     * @return 路由策略实例
     */
    private static RouterStrategy createFlatStrategy(String name) {
        if (name == null) {
            name = "failover";
        }
        return ServiceProvider.of(RouterStrategy.class).getNewExtension(name.toLowerCase());
    }

    /**
     * 解析聚合配置中的分组信息。
     * 支持两种配置模式：
     * <ul>
     *   <li>顶级 clients 列表（非 hybrid 策略）</li>
     *   <li>分组列表（hybrid 策略）</li>
     * </ul>
     *
     * @param config 聚合配置
     * @return 解析结果，包含所有客户端和分组路由器
     */
    private AllParsed parseGroups(AggregateChatClientSetting config) {
        List<RouterStrategy.WeightedClient> allClients = new ArrayList<>();
        List<HybridStrategy.GroupRouter> groupRouters = new ArrayList<>();

        List<AggregateChatClientSetting.GroupConfig> groups = config.getGroups();
        if (groups == null || groups.isEmpty()) {
            List<AggregateChatClientSetting.ClientConfig> top = config.getClients();
            if (top != null && !top.isEmpty()) {
                List<RouterStrategy.WeightedClient> gClients = buildClients(top, skillManager);
                allClients.addAll(gClients);
                RouterStrategy s = createFlatStrategy(config.getStrategy());
                groupRouters.add(new HybridStrategy.GroupRouter(
                        "default", null, s, gClients));
            }
        } else {
            for (AggregateChatClientSetting.GroupConfig g : groups) {
                if (g.getClients() == null || g.getClients().isEmpty()) {
                    continue;
                }
                List<RouterStrategy.WeightedClient> gClients = buildClients(g.getClients(), skillManager);
                allClients.addAll(gClients);
                RouterStrategy s = createFlatStrategy(g.getStrategy());
                Predicate<String> cond = parseCondition(g.getCondition());
                // 合并 token 分组条件
                if (g.getTokenGroups() != null && !g.getTokenGroups().isEmpty()) {
                    Predicate<String> tokenCond = p -> {
                        String tg = CURRENT_TOKEN_GROUP.get();
                        return g.isTokenGroupAllowed(tg);
                    };
                    cond = cond != null ? cond.and(tokenCond) : tokenCond;
                }
                groupRouters.add(new HybridStrategy.GroupRouter(
                        g.getName(), cond, s, gClients));
            }
        }
        return new AllParsed(allClients, groupRouters);
    }

    /**
     * 构建客户端实例列表（无 SkillManager）。
     *
     * @param configs 客户端配置列表
     * @return 带权重的客户端列表
     */
    private static List<RouterStrategy.WeightedClient> buildClients(
            List<AggregateChatClientSetting.ClientConfig> configs) {
        return buildClients(configs, null);
    }

    /**
     * 构建客户端实例列表。
     * 对每个配置创建 ChatClient，并将 Skill 注入 system prompt。
     *
     * @param configs      客户端配置列表
     * @param skillManager Skill 管理器
     * @return 带权重的客户端列表
     */
    private static List<RouterStrategy.WeightedClient> buildClients(
            List<AggregateChatClientSetting.ClientConfig> configs,
            SkillManager skillManager) {
        List<RouterStrategy.WeightedClient> result = new ArrayList<>();
        for (AggregateChatClientSetting.ClientConfig cc : configs) {
            if (cc.getProvider() == null || cc.getApiKey() == null) {
                log.warn("[Aggregate] client missing provider/apiKey, skipping");
                continue;
            }
            String system = cc.getSystem();
            if (skillManager != null && system != null) {
                system = SkillPrompt.inject(system, skillManager);
            } else if (skillManager != null) {
                system = SkillPrompt.inject(null, skillManager);
            }
            cc.setSystem(system);
            ChatClient client = cc.toChatClient();
            result.add(new RouterStrategy.WeightedClient(
                    cc.getProvider(),
                    cc.getModel() != null ? cc.getModel() : "default",
                    cc.getWeight(),
                    client));
        }
        return result;
    }

    /**
     * 解析条件表达式。
     * 支持两种简单条件：
     * <ul>
     *   <li>{@code prompt.length < N} — 提示词长度小于 N</li>
     *   <li>{@code prompt.length > N} — 提示词长度大于 N</li>
     * </ul>
     * 其他表达式将被记录警告并返回 null。
     *
     * @param condition 条件表达式字符串
     * @return 条件谓词，无法解析时返回 null
     */
    private static Predicate<String> parseCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        String expr = condition.trim();
        if (expr.startsWith("prompt.length") && expr.contains("<")) {
            try {
                int threshold = Integer.parseInt(expr.split("<")[1].trim());
                return p -> p != null && p.length() < threshold;
            } catch (NumberFormatException e) {
                // 解析失败，忽略
            }
        }
        if (expr.startsWith("prompt.length") && expr.contains(">")) {
            try {
                int threshold = Integer.parseInt(expr.split(">")[1].trim());
                return p -> p != null && p.length() > threshold;
            } catch (NumberFormatException e) {
                // 解析失败，忽略
            }
        }
        log.warn("[Aggregate] unsupported condition: {}", condition);
        return null;
    }

    /**
     * 解析结果内部记录。
     *
     * @param allClients   所有客户端实例
     * @param groupRouters 分组路由器列表
     */
    private record AllParsed(
            List<RouterStrategy.WeightedClient> allClients,
            List<HybridStrategy.GroupRouter> groupRouters
    ) {
    }
}
