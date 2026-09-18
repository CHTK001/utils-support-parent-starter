package com.chua.ollama.support;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.audio.VirtualClient;
import io.github.ollama4j.models.ps.ModelProcessesResult;

import java.util.List;

/**
 * Ollama 模块冒烟测试（非 JUnit，直接 main 运行）。
 *
 * <p>验证各门面（Chat / Embedding / Feature / Image / Audio / Manager）
 * 通过 SPI 创建并调用 Ollama 服务。
 * 前置条件：本机 Ollama 已运行（{@code ollama serve}）且至少拉取了一个模型
 * （如 {@code ollama pull hf.co/openbmb/MiniCPM5-2B-GGUF:Q4_K_M}）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OllamaSmokeTest {

    /**
     * 执行 Ollama 门面冒烟测试。
     *
     * @param args 运行参数（可选：[0]=模型 名称，缺省 使用第一个 已 安装 模型）
     */
    public static void main(String[] args) {
        // 1. Chat 门面
        ChatClient client = ChatClient.create("ollama", "");
        System.out.println("[Ollama-Chat] 查询 模型列表 ...");
        List<ModelDefinition> models = client.models();
        if (models.isEmpty()) {
            System.err.println("[Ollama] 没有 已 安装 模型，请先运行: ollama pull <model>");
            return;
        }
        for (ModelDefinition m : models) {
            System.out.printf("[Ollama-Chat]   %s%n", m.getId());
        }
        String modelId = args.length > 0 ? args[0] : models.getFirst().getId();
        System.out.printf("[Ollama-Chat] 使用 模型: %s%n", modelId);
        try {
            ChatSyncResponse response = client.model(modelId)
                    .temperature(1.0)
                    .topP(0.95)
                    .maxTokens(128)
                    .chatSyncWithResponse("你好！请 用 一 句话 介绍 自己。");
            System.out.printf("[Ollama-Chat] 响应: %s%n",
                    response != null ? response.getText() : "(空)");
            if (response != null && response.getUsage() != null) {
                System.out.printf("[Ollama-Chat] 用量: input=%s output=%s%n",
                        response.getUsage().getInputTokens(), response.getUsage().getOutputTokens());
            }
        } catch (Exception e) {
            System.err.println("[Ollama-Chat] 对话 失败: " + e.getMessage());
        } finally {
            client.close();
        }

        // 2. Embedding 门面（需要 嵌入 模型；无 嵌入 模型 时 仅 验证 可创建）
        EmbeddingClient embClient = EmbeddingClient.create("ollama", "");
        System.out.println("[Ollama-Embedding] 已创建，模型数: " + embClient.models().size());
        embClient.close();

        // 3. Feature 门面
        FeatureClient featClient = FeatureClient.create("ollama", "");
        System.out.println("[Ollama-Feature] 已创建，模型数: " + featClient.models().size());
        featClient.close();

        // 4. Image 门面
        ImageClient imgClient = ImageClient.create("ollama", "");
        System.out.println("[Ollama-Image] 已创建，模型数: " + imgClient.models().size());
        imgClient.close();

        // 5. Audio 门面
        VirtualClient audioClient = VirtualClient.create("ollama", "");
        System.out.println("[Ollama-Audio] 已创建，模型数: " + audioClient.models().size());
        audioClient.close();

        // 6. Manager 门面（原生 管理 能力）
        OllamaManager manager = new OllamaManager();
        try {
            boolean up = manager.ping();
            String version = manager.getVersion();
            System.out.printf("[Ollama-Manager] ping=%s version=%s%n", up, version);
            ModelProcessesResult ps = manager.ps();
            System.out.printf("[Ollama-Manager] 已 加载 模型 数: %d%n",
                    ps != null && ps.getModels() != null ? ps.getModels().size() : 0);
        } catch (Exception e) {
            System.err.println("[Ollama-Manager] 管理 调用 失败: " + e.getMessage());
        }

        System.out.println("[Ollama] 全部 门面 测试完成");
    }
}
