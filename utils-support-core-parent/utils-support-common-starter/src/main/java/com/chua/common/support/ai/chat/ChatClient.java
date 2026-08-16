package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.chat.usage.UsagePersistChatClient;
import com.chua.common.support.ai.generation.ImageGenerationResult;
import com.chua.common.support.ai.generation.ImageGenerationSpec;
import com.chua.common.support.ai.generation.VideoGenerationResult;
import com.chua.common.support.ai.generation.VideoGenerationSpec;
import com.chua.common.support.ai.probe.ProbeProgress;
import com.chua.common.support.ai.probe.ProbeReport;
import com.chua.common.support.ai.skill.DefaultSkillManager;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * AI 对话客户端接口
 *
 * <p>提供统一的 AI 大模型对话调用抽象，支持同步、异步和流式三种调用方式。
 * 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
 *
 * <p>链式配置示例：
 * <pre>{@code
 *   String answer = ChatClient.create("openai", "sk-xxx")
 *       .model("gpt-4")
 *       .temperature(0.7)
 *       .system("你是一名助手")
 *       .chatSync("你好");
 * }</pre>
 *
 * <p>流式调用示例：
 * <pre>{@code
 *   ChatClient.create("openai", "sk-xxx")
 *       .model("gpt-4")
 *       .chat("讲个故事", response -> {
 *           switch (response.getState()) {
 *               case START     -> System.out.println("开始");
 *               case STREAMING -> System.out.print(response.getContent());
 *               case STOP      -> System.out.println("\n完成");
 *               case ERROR     -> System.err.println(response.getErrorMessage());
 *           }
 *       });
 * }</pre>
 *
 * @author CH
 * @since 2026/07/15
 */
public interface ChatClient extends AutoCloseable {

    /**
     * 通过完整配置创建 AI 对话客户端
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl 等
     * @return ChatClient 实例
     */
    static ChatClient create(ChatClientSetting setting) {
        return ServiceProvider.of(ChatClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 的 AI 对话客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @return ChatClient 实例
     */
    static ChatClient create(String provider, String apiKey) {
        return create(ChatClientSetting.builder()
                .provider(provider).appKey(apiKey).build());
    }

    /**
     * 创建指定 provider、密钥和自定义地址的 AI 对话客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 地址
     * @return ChatClient 实例
     */
    static ChatClient create(String provider, String apiKey, String baseUrl) {
        return create(ChatClientSetting.builder()
                .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 包装指定 ChatClient，添加异步用量持久化能力
     *
     * <p>每次 {@link #chatSyncWithResponse(String)} 调用完成后，
     * 自动通过 Engine ORM 异步持久化 {@code AiUsage}，不阻塞调用线程。
     *
     * @param client 待包装的 ChatClient 实例
     * @param engine Engine 实例
     * @return 包装后的 ChatClient
     */
    static ChatClient withUsagePersistence(ChatClient client, Engine engine) {
        return UsagePersistChatClient.wrap(client, engine);
    }

    /**
     * 设置 AI 服务商
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称
     *
     * @param model 模型名称，如 "gpt-4"、"deepseek-chat"
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient model(String model) {
        return this;
    }

    /**
     * 设置系统提示词
     *
     * @param system 系统提示词内容
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient system(String system) {
        return this;
    }

    /**
     * 设置温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     * 值越低输出越确定，值越高输出越多样。
     *
     * @param temperature 温度值
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient temperature(double temperature) {
        return this;
    }

    /**
     * 设置最大输出 Token 数
     *
     * @param maxTokens 最大 Token 数量
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient maxTokens(int maxTokens) {
        return this;
    }

    /**
     * 添加图片附件
     *
     * <p>仅多模态模型支持图片输入。
     *
     * @param imageUrl 图片 URL 或 Base64 数据 URI
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addImage(String imageUrl) {
        return this;
    }

    /**
     * 添加用户历史消息
     *
     * <p>将用户消息追加到对话历史中，用于多轮对话上下文。
     *
     * @param content 消息内容
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addUserHistory(String content) {
        return this;
    }

    /**
     * 添加助手历史消息
     *
     * <p>将助手消息追加到对话历史中，用于多轮对话上下文。
     *
     * @param content 消息内容
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addAssistantHistory(String content) {
        return this;
    }

    /**
     * 添加对话历史
     *
     * <p>一次性设置完整的对话历史消息列表，覆盖之前添加的历史。
     * 用于多轮对话时传递完整上下文。
     *
     * @param messages 对话历史消息列表，按时间正序排列
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient history(List<ChatMessage> messages) {
        return this;
    }

    /**
     * 设置工具（函数调用）定义列表
     *
     * <p>一次设置多个可被模型调用的工具，覆盖之前添加的工具。
     * 各实现类据此构建对应的 function calling 请求参数。
     *
     * @param tools 工具定义列表
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient tools(List<ChatTool> tools) {
        return this;
    }

    /**
     * 设置是否启用深度思考模式
     *
     * <p>启用后模型会输出推理过程（思维链），
     * {@link ChatResponse#getReasoningContent()} 可获得思考内容。
     * 仅支持思考模式的模型有效，其他模型忽略此参数。
     *
     * @param thinking true 启用深度思考
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient thinking(boolean thinking) {
        return this;
    }

    /**
     * 设置深度思考强度
     *
     * <p>独立于 {@link #thinking(boolean)}，用于控制思考模式的强度级别。
     * 取值约定：{@code low} / {@code medium} / {@code high}。
     *
     * <ul>
     *   <li>OpenAI：映射 {@code reasoning_effort}</li>
     *   <li>Claude：映射 {@code budget_tokens}（不足时忽略）</li>
     *   <li>Gemini：映射 {@code thinkingBudget}</li>
     *   <li>其他：视服务商支持情况，不支持时忽略</li>
     * </ul>
     *
     * @param effort 思考强度级别（low / medium / high）
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient thinkingEffort(String effort) {
        return this;
    }

    /**
     * 设置是否启用智能搜索
     *
     * <p>启用后模型在需要时可自动联网搜索实时信息。
     * 仅支持搜索能力的模型有效，其他模型忽略此参数。
     *
     * @param smartSearch true 启用智能搜索
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient smartSearch(boolean smartSearch) {
        return this;
    }

    /**
     * 设置技能管理器
     *
     * <p>将注册的技能说明注入系统提示词，使模型感知可用技能。
     * 技能通过 {@link SkillPrompt#inject(String, SkillManager)} 拼入 system prompt，
     * 不依赖 function calling 能力，适用于不支持工具调用的模型。
     *
     * <p>使用示例：
     * <pre>{@code
     * SkillManager sm = new DefaultSkillManager()
     *     .register(SkillDefinition.skill("get_weather",
     *         "获取天气", args, handler)));
     * client.skill(sm).chatSync("今天天气如何？");
     * }</pre>
     *
     * @param skillManager 技能管理器
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient skill(SkillManager skillManager) {
        return this;
    }

    /**
     * 设置单个技能定义
     *
     * <p>将单个技能包装进内部 {@link SkillManager}，与 {@link #skill(SkillManager)}
     * 效果一致，适用于只注入一个技能的简洁场景。
     *
     * <p>使用示例（直接传文本内容）：
     * <pre>{@code
     * client.skill(SkillDefinition.text("get_weather", "获取天气",
     *     "# 天气技能\n当用户询问天气时返回晴天。"));
     * }</pre>
     *
     * @param skillDefinition 技能定义
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient skill(SkillDefinition skillDefinition) {
        if (skillDefinition == null) {
            return this;
        }
        return skill(new DefaultSkillManager().register(skillDefinition));
    }

    /**
     * 追加一个工具（函数调用）定义
     *
     * <p>与 {@link #tools(List)} 不同，此方法向现有工具列表追加单个工具。
     *
     * @param tool 工具定义
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient tool(ChatTool tool) {
        return this;
    }

    /**
     * 设置工具选择策略（tool_choice）
     *
     * <p>取值约定：
     * <ul>
     *   <li>auto — 由模型自行决定是否调用工具（默认）</li>
     *   <li>none — 禁止模型调用工具</li>
     *   <li>required — 强制模型必须调用工具</li>
     *   <li>工具名称 — 强制模型调用指定工具</li>
     * </ul>
     *
     * @param toolChoice 工具选择策略
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient toolChoice(String toolChoice) {
        return this;
    }

    /**
     * 设置 Top-P 采样参数
     *
     * <p>核采样参数，控制生成文本的多样性，取值范围 [0.0, 1.0]。
     *
     * @param topP Top-P 值
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient topP(Double topP) {
        return this;
    }

    /**
     * 设置停止序列
     *
     * <p>当模型输出命中任一停止序列时终止生成。
     *
     * @param stop 停止序列列表
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient stop(List<String> stop) {
        return this;
    }

    /**
     * 设置随机种子
     *
     * <p>指定后模型在相同输入下尽量产生确定性输出。
     *
     * @param seed 随机种子
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient seed(Long seed) {
        return this;
    }

    /**
     * 设置响应格式
     *
     * <p>取值约定：
     * <ul>
     *   <li>text — 普通文本（默认）</li>
     *   <li>json_object — 强制返回 JSON 对象</li>
     *   <li>json_schema — 按 JSON Schema 约束结构化输出</li>
     * </ul>
     *
     * @param responseFormat 响应格式
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient responseFormat(String responseFormat) {
        return this;
    }

    /**
     * 设置额外请求体参数
     *
     * <p>透传给各服务商请求体的额外字段（如 frequency_penalty、
     * presence_penalty、max_completion_tokens 等），用于覆盖标准参数之外的能力。
     *
     * @param extraBody 额外请求体参数键值映射
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient extraBody(Map<String, Object> extraBody) {
        return this;
    }

    /**
     * 添加文件附件
     *
     * <p>上传文件作为当前对话的附件，供多模态模型解析。
     * 支持图片、PDF、Word、代码文件等多种格式。
     *
     * @param name     文件名
     * @param data     文件字节数据
     * @param mimeType MIME 类型，如 "image/png"、"application/pdf"
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addAttachment(String name, byte[] data, String mimeType) {
        return this;
    }

    /**
     * 添加远程文件附件
     *
     * <p>通过 URL 引用远程文件作为附件，不直接上传字节数据。
     *
     * @param name     文件名
     * @param url      文件访问地址
     * @param mimeType MIME 类型
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        return this;
    }

    /**
     * 设置会话 ID
     *
     * <p>用于在多轮对话中关联同一会话的上下文。
     *
     * @param sessionId 会话标识
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient session(String sessionId) {
        return this;
    }

    /**
     * 开启新会话
     *
     * <p>清空当前对话历史，开始一个新的对话。
     *
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient newChat() {
        return this;
    }

    /**
     * 流式对话
     *
     * <p>以流式方式发送对话请求，每次收到服务端返回的内容片段时回调 consumer。
     * consumer 接收的 {@link ChatResponse} 对象包含完整的生命周期事件：
     * <ul>
     *   <li>{@link ChatResponse.State#START} — 请求开始</li>
     *   <li>{@link ChatResponse.State#STREAMING} — 内容片段</li>
     *   <li>{@link ChatResponse.State#STOP} — 请求结束</li>
     *   <li>{@link ChatResponse.State#ERROR} — 请求出错</li>
     * </ul>
     *
     * @param prompt   用户输入
     * @param consumer 流式响应回调，接收 {@link ChatResponse} 事件对象
     */
    default void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, throwable -> {
            throw new RuntimeException(throwable);
        });
    }

    /**
     * 流式对话（含完成回调和错误回调）
     *
     * <p>默认实现：将同步调用包装为流式事件序列。非流式实现类可直接使用此默认方法，
     * 流式实现类应覆写此方法以提供真正的流式体验。
     *
     * @param prompt      用户输入
     * @param consumer    流式响应回调
     * @param onComplete  完成回调，所有事件发送完毕后执行
     * @param onError     错误回调
     */
    default void chat(String prompt, Consumer<ChatResponse> consumer,
                      Runnable onComplete, Consumer<Throwable> onError) {
        try {
            ChatSyncResponse syncResponse = chatSyncWithResponse(prompt);
            if (syncResponse != null && syncResponse.getText() != null) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content(syncResponse.getText())
                        .build());
            }
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .usage(syncResponse != null ? syncResponse.getUsage() : null)
                    .build());
            onComplete.run();
        } catch (Throwable e) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 同步对话
     *
     * <p>发送请求并等待完整响应，返回全部文本内容。
     *
     * @param prompt 用户输入
     * @return 完整响应文本
     */
    String chatSync(String prompt);

    /**
     * 同步对话（带超时）
     *
     * <p>发送请求并等待完整响应，支持超时控制。
     * 默认实现忽略超时参数，直接调用 {@link #chatSync(String)}。
     *
     * @param prompt         用户输入
     * @param timeoutMillis  超时时间（毫秒），小于等于 0 表示不超时
     * @return 完整响应文本
     */
    default String chatSync(String prompt, long timeoutMillis) {
        return chatSync(prompt);
    }

    /**
     * 同步对话（返回完整响应对象）
     *
     * <p>发送请求并等待完整响应，返回包含文本内容和用量信息的响应对象。
     * 与 {@link #chatSync(String)} 仅返回纯文本不同，本方法返回的
     * {@link ChatSyncResponse} 额外携带 Token 用量、费用等计量信息。
     *
     * <p>默认实现基于 {@link #chatSync(String)} 包装，不包含用量信息。
     * 实现类应覆写此方法以提供完整的用量数据。
     *
     * @param prompt 用户输入
     * @return 包含文本和用量信息的完整响应对象
     */
    default ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    /**
     * 异步对话（返回包含用量信息的响应对象）
     *
     * <p>通过 CompletableFuture 异步执行同步对话，返回完整的
     * {@link ChatSyncResponse} 对象，包含文本内容和用量信息。
     *
     * @param prompt 用户输入
     * @return 异步任务，完成时返回包含文本和用量信息的响应对象
     */
    default CompletableFuture<ChatSyncResponse> chatAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> chatSyncWithResponse(prompt));
    }

    /**
     * 关闭客户端
     *
     * <p>释放底层资源，如 HTTP 连接池等。
     */
    @Override
    default void close() {
    }

    /**
     * 获取客户端权重（用于加权路由策略）
     *
     * <p>默认权重为 1，可由实现类覆盖以提供自定义权重。
     *
     * @return 权重值，必须大于等于 0
     */
    default int weight() {
        return 1;
    }

    /**
     * 获取服务商支持的模型列表
     *
     * <p>调用 {@code GET {baseUrl}/v1/models} 接口查询可用模型。
     * 返回包含模型 ID、名称、描述、能力等信息的 {@link ModelDefinition} 列表。
     * 默认返回空列表，子类可按需覆写。
     *
     * @return 可用模型定义列表
     */
    default List<ModelDefinition> models() {
        return List.of();
    }

    /**
     * 查询当前能力下全部可用模型 ID。
     *
     * <p>基于 {@link #models()} 提取模型 ID 列表，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 ID 列表
     */
    default List<String> listModels() {
        List<ModelDefinition> defs = models();
        if (defs == null || defs.isEmpty()) {
            return List.of();
        }
        return defs.stream()
                .filter(d -> d != null && d.getId() != null && !d.getId().isBlank())
                .map(ModelDefinition::getId)
                .toList();
    }

    /**
     * 获取模型定价信息。
     *
     * <p>返回包含单价信息的模型定义列表，可用于费用估算和成本对比。
     * 默认返回空列表，实现类可按需覆写。
     *
     * @return 模型定价列表
     */
    default List<ModelDefinition> modelPricing() {
        return List.of();
    }

    /**
     * 执行 AI 中转站真伪探测。
     *
     * <p>基于多维度交叉验证策略，探测 OpenAI 兼容 API 中转站背后真实使用的模型。
     * 返回包含各维度探测结果、综合置信度和最终判词的 {@link ProbeReport}。
     * 默认抛出 {@link UnsupportedOperationException}，支持探测的实现类（如 {@link OpenAiChatClient}）
     * 应覆写此方法以提供真实探测能力。
     *
     * @return 真伪探测综合报告
     * @throws UnsupportedOperationException 当前实现不支持探测功能
     */
    default ProbeReport probe() {
        throw new UnsupportedOperationException("当前 ChatClient 实现不支持探测功能");
    }

    /**
     * 生成图像。
     *
     * <p>根据文本描述生成图像。各服务商实现应映射到自身的图像生成 API。
     * 不支持的实现应保持默认抛出 {@link UnsupportedOperationException}。
     *
     * @param prompt       图像描述文本
     * @param ratio        宽高比（如 "1:1", "16:9", "9:16"），部分服务商支持
     * @param n            生成数量（部分服务商支持，如 OpenAI DALL-E 支持 n=1~10）
     * @param width        输出宽度（部分服务商支持）
     * @param height       输出高度（部分服务商支持）
     * @param quality      质量（"standard" / "hd"），部分服务商支持
     * @param refImageKey  参考图标识（如豆包图生图），不支持时忽略
     * @return 图像生成结果
     * @throws UnsupportedOperationException 当前实现不支持图像生成
     */
    default ImageGenerationResult generateImage(String prompt, String ratio, int n,
                                                int width, int height, String quality,
                                                String refImageKey) {
        throw new UnsupportedOperationException("当前 ChatClient 实现不支持图像生成");
    }

    /**
     * 生成图像（简化参数）。
     *
     * @param prompt 图像描述文本
     * @param ratio  宽高比，可为空
     * @return 图像生成结果
     * @see #generateImage(String, String, int, int, int, String, String)
     */
    default ImageGenerationResult generateImage(String prompt, String ratio) {
        return generateImage(prompt, ratio, 1, 0, 0, null, null);
    }

    /**
     * 创建图像生成参数构建器（链式调用）。
     *
     * <p>用法：
     * <pre>{@code
     * ImageGenerationResult result = client.generateImage()
     *     .prompt("一只柴犬在樱花树下")
     *     .ratio("16:9")
     *     .n(3)
     *     .generate();
     * }</pre>
     *
     * @return 图像生成参数构建器
     */
    default ImageGenerationSpec generateImage() {
        return new ImageGenerationSpec(this);
    }

    /**
     * 生成视频。
     *
     * <p>根据文本描述生成视频。各服务商实现应映射到自身的视频生成 API。
     * 不支持的实现应保持默认抛出 {@link UnsupportedOperationException}。
     *
     * @param prompt         视频描述文本
     * @param ratio          宽高比，可为空
     * @param cameraMovement 镜头运动描述，可为空
     * @param refImageKey    参考图标识，可为空
     * @param timeoutSeconds 超时秒数（用于异步轮询场景）
     * @return 视频生成结果
     * @throws UnsupportedOperationException 当前实现不支持视频生成
     */
    default VideoGenerationResult generateVideo(String prompt, String ratio,
                                                String cameraMovement, String refImageKey,
                                                int timeoutSeconds) {
        throw new UnsupportedOperationException("当前 ChatClient 实现不支持视频生成");
    }

    /**
     * 生成视频（简化参数）。
     *
     * @param prompt 视频描述文本
     * @param ratio  宽高比，可为空
     * @return 视频生成结果
     * @see #generateVideo(String, String, String, String, int)
     */
    default VideoGenerationResult generateVideo(String prompt, String ratio) {
        return generateVideo(prompt, ratio, null, null, 300);
    }

    /**
     * 创建视频生成参数构建器（链式调用）。
     *
     * <p>用法：
     * <pre>{@code
     * VideoGenerationResult result = client.generateVideo()
     *     .prompt("一只柴犬在雪地里奔跑")
     *     .ratio("16:9")
     *     .cameraMovement("推进")
     *     .generate();
     * }</pre>
     *
     * @return 视频生成参数构建器
     */
    default VideoGenerationSpec generateVideo() {
        return new VideoGenerationSpec(this);
    }
}