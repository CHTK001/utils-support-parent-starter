package com.chua.jitllm.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.beehive.jitllm.api.GenerationRequest;
import org.beehive.jitllm.api.GenerationResult;
import org.beehive.jitllm.api.GenerationSession;
import org.beehive.jitllm.api.TextGenerationModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * jitLLM 本地 GPU 大模型对话客户端（SPI provider="jitllm"）。
 *
 * <p>基于 beehive-lab jitllm（TornadoVM GPU 加速）加载 GGUF 模型实现本地推理，
 * 支持纯文本对话、多轮历史、流式输出与用量统计。模型文件路径通过
 * {@code model}（GGUF 文件名或路径）与 {@code baseUrl}（模型目录，可选）定位，
 * 本地推理无需 API Key。
 *
 * <p>调用示例：
 * <pre>{@code
 *   // 纯文本对话（model 指向本地 GGUF 文件）
 *   String answer = ChatClient.create("jitllm", "")
 *       .model("Qwen3-0.6B-Q8_0.gguf")
 *       .temperature(0.8)
 *       .maxTokens(512)
 *       .chatSync("你好，介绍一下你自己");
 *
 *   // 多轮历史
 *   String reply = ChatClient.create("jitllm", "")
 *       .model("models/Qwen3-0.6B-Q8_0.gguf")
 *       .addUserHistory("我叫小明")
 *       .chatSync("你还记得我叫什么吗？");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
@Spi(JitllmConstants.PROVIDER)
public class JitllmChatClient implements ChatClient {

    /**
     * 客户端 配置
     */
    private final ChatClientSetting setting;

    /**
     * 当前 模型 名称（GGUF 文件名或路径）
     */
    private String model;

    /**
     * 系统 提示词
     */
    private String system;

    /**
     * 采样 温度
     */
    private Double temperature;

    /**
     * 最大 输出 令牌 数
     */
    private Integer maxTokens;

    /**
     * Top-P 采样
     */
    private Double topP;

    /**
     * 增量 历史（按 时间 正序）
     */
    private final List<ChatMessage> history = Wraps.list();

    /**
     * 外部 一次性 历史（覆盖 增量 历史）
     */
    private List<ChatMessage> externalHistory;

    /**
     * 创建 jitLLM 对话客户端。
     *
     * <p>构造阶段只解析模型路径，不加载权重；模型在首次对话时才装载进
     * GPU/内存，避免无推理场景的启动开销。本地推理无需 API Key。</p>
     *
     * @param setting 客户端配置（provider 应为 "jitllm"，model 为 GGUF 文件路径）
     */
    public JitllmChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.model = setting != null ? setting.getModel() : null;
        this.system = setting != null ? setting.getSystem() : null;
        this.temperature = setting != null ? setting.getTemperature() : null;
        this.maxTokens = setting != null ? setting.getMaxTokens() : null;
        this.topP = setting != null ? setting.getTopP() : null;
    }

    /**
     * 模型
     */
    @Override
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    /**
     * 系统
     */
    @Override
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    /**
     * 温度
     */
    @Override
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    /**
     * 最大令牌
     */
    @Override
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    /**
     * Top-P
     */
    @Override
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    /**
     * 用户历史
     */
    @Override
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    /**
     * 助手历史
     */
    @Override
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    /**
     * 历史
     */
    @Override
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    /**
     * 新会话
     */
    @Override
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        return this;
    }

    /**
     * 对话同步
     */
    @Override
    public String chatSync(String prompt) {
        return chatSyncWithResponse(prompt).getText();
    }

    /**
     * 对话同步带响应
     */
    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        TextGenerationModel generation = currentModel();
        try (GenerationSession session = generation.newSession()) {
            GenerationResult result = session.generate(buildRequest(prompt, null));
            return ChatSyncResponse.builder()
                    .text(result.text())
                    .usage(parseUsage(result))
                    .build();
        } catch (Exception e) {
            throw JitllmSupport.wrap("chat", e);
        }
    }

    /**
     * 流式对话（含完成回调和错误回调）。
     *
     * <p>基于 jitLLM {@code onToken} 回调实现真流式：START → STREAMING* → STOP，
     * 异常时回调 ERROR 并交由 {@code onError} 处理。</p>
     *
     * @param prompt     用户输入
     * @param consumer   流式响应回调
     * @param onComplete 完成回调
     * @param onError    错误回调
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        try {
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
            TextGenerationModel generation = currentModel();
            try (GenerationSession session = generation.newSession()) {
                GenerationResult result = session.generate(buildRequest(prompt, token ->
                        consumer.accept(ChatResponse.builder()
                                .state(ChatResponse.State.STREAMING)
                                .content(token)
                                .build())));
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STOP)
                        .usage(parseUsage(result))
                        .build());
            }
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
     * 模型列表。
     *
     * <p>本地推理无远端模型端点：配置了模型目录（{@code baseUrl} 或模型文件父目录）时
     * 扫描目录下的 {@code *.gguf} 文件；无法定位目录时仅返回当前配置的模型。
     * 上下文窗口等元数据在未加载模型时不可得，按契约保持未知不臆造。</p>
     *
     * @return 可用模型定义列表
     */
    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> result = Wraps.list();
        Path dir = resolveModelDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".gguf");
                        })
                        .sorted()
                        .forEach(p -> result.add(ModelDefinition.builder()
                                .id(p.getFileName().toString())
                                .name(p.getFileName().toString())
                                .provider(JitllmConstants.PROVIDER)
                                .description("本地 jitLLM GGUF 模型")
                                .capabilities(List.of("chat"))
                                .build()));
            } catch (IOException e) {
                log.warn("[JitLLM] 扫描模型目录异常: {}", e.getMessage());
            }
        }
        if (result.isEmpty() && StringUtils.isNotBlank(model)) {
            result.add(ModelDefinition.builder()
                    .id(model)
                    .name(model)
                    .provider(JitllmConstants.PROVIDER)
                    .description("本地 jitLLM GGUF 模型")
                    .capabilities(List.of("chat"))
                    .build());
        }
        return result;
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        newChat();
    }

    /**
     * 获取当前客户端配置
     *
     * @return ChatClientSetting 配置对象
     */
    @Override
    public ChatClientSetting getSetting() {
        return setting;
    }

    /**
     * 获取当前模型名称
     *
     * @return 模型名称（GGUF 文件名或路径）
     */
    @Override
    public String getModel() {
        return model;
    }

    /**
     * 解析当前生效的文本生成模型。
     *
     * @return 文本生成模型
     */
    private TextGenerationModel currentModel() {
        ChatClientSetting effective = withModel();
        return JitllmSupport.loadModel(JitllmSupport.resolveModelPath(effective));
    }

    /**
     * 以当前链式 model 覆盖后的配置解析模型路径。
     *
     * @return 生效配置
     */
    private ChatClientSetting withModel() {
        if (setting == null) {
            return ChatClientSetting.builder().model(model).build();
        }
        if (StringUtils.isBlank(model)) {
            return setting;
        }
        return ChatClientSetting.builder()
                .provider(setting.getProvider())
                .baseUrl(setting.getBaseUrl())
                .model(model)
                .build();
    }

    /**
     * 构建 jitLLM 生成请求（消息、采样参数与可选的流式回调）。
     *
     * @param prompt  用户输入
     * @param onToken 流式回调，可为空（空表示非流式）
     * @return 生成请求
     */
    private GenerationRequest buildRequest(String prompt, Consumer<String> onToken) {
        List<ChatMessage> effective = externalHistory != null ? externalHistory : history;
        GenerationRequest.Builder builder = GenerationRequest.builder()
                .messages(JitllmSupport.toJitllmMessages(setting, system, effective, prompt))
                .maxNewTokens(maxTokens != null && maxTokens > 0 ? maxTokens : JitllmConstants.DEFAULT_MAX_NEW_TOKENS);
        if (temperature != null) {
            builder.temperature(temperature.floatValue());
        }
        if (topP != null) {
            builder.topP(topP.floatValue());
        }
        if (onToken != null) {
            builder.onToken(onToken);
        }
        return builder.build();
    }

    /**
     * 解析令牌用量。
     *
     * @param result jitLLM 生成结果
     * @return 用量对象；无用量信息时返回空
     */
    private AiUsage parseUsage(GenerationResult result) {
        if (result == null) {
            return null;
        }
        int promptTokens = result.promptTokens();
        int completionTokens = result.generatedTokens();
        if (promptTokens <= 0 && completionTokens <= 0) {
            return null;
        }
        return AiUsage.builder()
                .inputTokens(promptTokens)
                .outputTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .model(model)
                .provider(JitllmConstants.PROVIDER)
                .build();
    }

    /**
     * 解析模型目录（baseUrl 优先，其次当前模型文件父目录）。
     *
     * @return 模型目录；无法定位时返回 null
     */
    private Path resolveModelDir() {
        if (setting != null && StringUtils.isNotBlank(setting.getBaseUrl())) {
            return Path.of(setting.getBaseUrl().trim());
        }
        if (StringUtils.isNotBlank(model)) {
            Path direct = Path.of(model.trim());
            Path parent = direct.toAbsolutePath().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        return null;
    }
}
