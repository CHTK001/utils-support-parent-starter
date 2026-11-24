package com.chua.jitllm.support;

import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.beehive.jitllm.api.ChatRole;
import org.beehive.jitllm.api.LocalModel;
import org.beehive.jitllm.api.LocalModels;
import org.beehive.jitllm.api.ModelOptions;
import org.beehive.jitllm.api.TextGenerationModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * jitLLM 支持工具。
 *
 * <p>封装 GGUF 模型路径解析、{@link TextGenerationModel} 的进程级缓存加载、
 * 对话消息转换与异常包装，供 {@link JitllmChatClient} 共用。
 *
 * <p>模型加载（权重映射 + TornadoVM 内核编译）开销大，按模型路径做进程级缓存，
 * 多个客户端实例共享同一底层模型；需要释放时调用 {@link #releaseAll()}。
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public final class JitllmSupport {

    /**
     * 已加载模型缓存（key = 模型文件绝对路径）
     */
    private static final Map<String, TextGenerationModel> MODEL_CACHE = new ConcurrentHashMap<>();

    /**
     * JitllmSupport 实例
     */
    private JitllmSupport() {
    }

    /**
     * 解析 GGUF 模型文件路径。
     *
     * <p>解析口径：
     * <ul>
     *   <li>{@code model} 为空 → 抛出非法参数异常；</li>
     *   <li>{@code model} 是存在的绝对路径 → 直接使用；</li>
     *   <li>否则按「模型目录 + 模型名」拼接：目录优先取 {@code baseUrl}，
     *       为空时回退 {@link JitllmConstants#DEFAULT_MODEL_DIR}；</li>
     *   <li>解析结果必须存在，否则抛出文件缺失异常。</li>
     * </ul>
     *
     * @param setting 客户端配置
     * @return 模型文件路径
     */
    public static Path resolveModelPath(ChatClientSetting setting) {
        String model = setting != null ? setting.getModel() : null;
        if (StringUtils.isBlank(model)) {
            throw new IllegalArgumentException("[JitLLM] 未指定模型：model 需为 GGUF 文件名或路径");
        }
        String modelValue = model.trim();
        Path direct = Path.of(modelValue);
        if (direct.isAbsolute() && Files.exists(direct)) {
            return direct;
        }
        String dir = setting.getBaseUrl();
        if (StringUtils.isBlank(dir)) {
            dir = JitllmConstants.DEFAULT_MODEL_DIR;
        }
        Path resolved = Path.of(dir.trim()).resolve(modelValue);
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("[JitLLM] 模型文件不存在: " + resolved.toAbsolutePath()
                    + "（model=" + modelValue + ", modelDir=" + dir + "）");
        }
        return resolved;
    }

    /**
     * 加载（或复用缓存中的）文本生成模型。
     *
     * <p>同一进程内同一路径的模型只加载一次；加载期间持有缓存锁，
     * 避免同一模型被并发重复装载进显存/内存。</p>
     *
     * @param modelPath GGUF 模型文件路径
     * @return 文本生成模型
     */
    public static TextGenerationModel loadModel(Path modelPath) {
        String key = modelPath.toAbsolutePath().normalize().toString();
        TextGenerationModel model = MODEL_CACHE.get(key);
        if (model != null) {
            return model;
        }
        synchronized (MODEL_CACHE) {
            model = MODEL_CACHE.get(key);
            if (model != null) {
                return model;
            }
            log.info("[JitLLM] 加载模型: {}", key);
            try {
                LocalModel local = LocalModels.load(modelPath, ModelOptions.defaults());
                if (!(local instanceof TextGenerationModel generation)) {
                    try (LocalModel ignored = local) {
                        throw new IllegalStateException("[JitLLM] 模型不支持文本生成: " + key);
                    }
                }
                MODEL_CACHE.put(key, generation);
                return generation;
            } catch (IOException e) {
                throw new RuntimeException("[JitLLM] 模型加载失败: " + key + " - " + e.getMessage(), e);
            }
        }
    }

    /**
     * 释放全部缓存的底层模型（权重与 GPU 资源）。
     *
     * <p>仅应在确认不再使用 jitLLM 客户端时调用（如宿主停机钩子）；
     * 日常 {@link JitllmChatClient#close()} 只清理会话历史，不卸载共享模型。</p>
     */
    public static void releaseAll() {
        synchronized (MODEL_CACHE) {
            for (TextGenerationModel model : MODEL_CACHE.values()) {
                try {
                    model.close();
                } catch (Exception e) {
                    log.warn("[JitLLM] 模型释放异常: {}", e.getMessage());
                }
            }
            MODEL_CACHE.clear();
        }
    }

    /**
     * 组装 jitLLM 消息列表（系统提示 + 历史 + 当前输入）。
     *
     * <p>当外部历史与增量历史均为空且无系统提示时，退化为单条用户消息，
     * 由 {@code prompt}/{@code systemPrompt} 承载语义。</p>
     *
     * @param setting 客户端配置（提供默认 system）
     * @param system  链式设置的系统提示词（优先于 setting）
     * @param history 对话历史（按时间正序）
     * @param prompt  当前用户输入
     * @return jitLLM 消息列表
     */
    public static List<org.beehive.jitllm.api.ChatMessage> toJitllmMessages(ChatClientSetting setting,
                                                                            String system,
                                                                            List<ChatMessage> history,
                                                                            String prompt) {
        List<org.beehive.jitllm.api.ChatMessage> messages = new ArrayList<>();
        String effectiveSystem = StringUtils.isNotBlank(system) ? system
                : (setting != null ? setting.getSystem() : null);
        if (StringUtils.isNotBlank(effectiveSystem)) {
            messages.add(org.beehive.jitllm.api.ChatMessage.of(ChatRole.SYSTEM, effectiveSystem));
        }
        if (history != null) {
            for (ChatMessage msg : history) {
                if (msg == null || StringUtils.isBlank(msg.getContent())) {
                    continue;
                }
                ChatRole role = "assistant".equalsIgnoreCase(msg.getRole()) ? ChatRole.ASSISTANT : ChatRole.USER;
                messages.add(org.beehive.jitllm.api.ChatMessage.of(role, msg.getContent()));
            }
        }
        messages.add(org.beehive.jitllm.api.ChatMessage.of(ChatRole.USER, prompt != null ? prompt : ""));
        return messages;
    }

    /**
     * 将异常转换为运行时异常并携带原始信息。
     *
     * @param action 操作描述
     * @param e      底层异常
     * @return 包装后的运行时异常
     */
    public static RuntimeException wrap(String action, Exception e) {
        return new RuntimeException("JitLLM " + action + " 失败: " + e.getMessage(), e);
    }
}
