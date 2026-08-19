package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.image.ImageClient;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

/**
 * 将 ImageClient 适配为 AgentScope 的 Model，使子 Agent 具备文生图能力。
 *
 * <p>AgentScope 只认文本 Model，本适配器将图像生成结果转为 base64 文本返回。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageGenerationModel implements Model {

    /** 图像客户端 */
    /** 图片客户端 */
    private final ImageClient imageClient;
    /** 模型名称 */
    private final String modelName;

    public ImageGenerationModel(ImageClient imageClient, String modelName) {
        this.imageClient = imageClient;
        this.modelName = modelName != null ? modelName : "image-generation";
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String prompt = extractPrompt(messages);
        String resultText;
        try {
            BufferedImage image = imageClient.generate(prompt);
            if (image == null) {
                resultText = "[Image generation returned empty result]";
            } else {
                resultText = "[Generated image (base64)]\n" + toBase64Png(image);
            }
        } catch (Exception e) {
            resultText = "[Image generation failed: " + e.getMessage() + "]";
        }

        ChatResponse response = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(resultText).build()))
                .finishReason("stop")
                .build();
        return Flux.just(response);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    private static String extractPrompt(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                return text != null ? text : "";
            }
        }
        return "";
    }

    private static String toBase64Png(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
