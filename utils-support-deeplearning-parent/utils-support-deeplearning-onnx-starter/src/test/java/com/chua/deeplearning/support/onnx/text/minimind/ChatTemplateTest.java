package com.chua.deeplearning.support.onnx.text.minimind;

import ai.djl.Model;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniMind chat template 流程测试。
 * <p>
 * 1. 用 Jinja 简版（{@link #renderSimpleChatTemplate}）构造 chat prompt
 * 2. 通过 DJL Predictor 走 MiniMindTranslator
 * 3. 验证多轮对话格式
 * </p>
 * <p>
 * minimind-3 自带的 chat_template.jinja 支持 system / user / assistant 三种 role，
 * 含 tool_call/think 等特殊 token。本测试不依赖 jinja2，纯 Java 实现最小集。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ChatTemplateTest {

    private static final String IM_START = "<|im_start|>";
    private static final String IM_END = "<|im_end|>";
    private static final String SYSTEM = "system";
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";

    public static void main(String[] args) throws Exception {
        String modelDir = System.getenv("MINIMIND_MODEL_DIR");
        if (modelDir == null) {
            if (Files.isDirectory(Paths.get("D:/ch/project/minimind-3"))) {
                modelDir = "D:/ch/project/minimind-3";
            } else {
                Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "chua-dl-models", "models", "minimind");
                NativeLoader.of("minimind-resources")
                        .from(ChatTemplateTest.class.getClassLoader())
                        .basePath("models/minimind/")
                        .toTarget(tmp)
                        .glob("*")
                        .withMd5(true)
                        .extractOnly(true)
                        .load();
                modelDir = tmp.toString();
            }
        }
        log.info("model dir: {}", modelDir);
        Path p = Path.of(modelDir);

        // ===== Test 1: 渲染 chat prompt =====
        String prompt = renderSimpleChatTemplate(
                new String[][]{
                        {SYSTEM, "你是一个AI助手"},
                        {USER, "你好，请介绍一下自己。"},
                },
                true // add_generation_prompt
        );
        log.info("=== Test 1: chat prompt ===\n{}", prompt);

        // ===== Test 2: 用 DJL Predictor 跑多轮 =====
        log.info("\n=== Test 2: DJL Predictor with chat prompt ===");
        Model model = Model.newInstance("minimind-chat", "OnnxRuntime");
        model.load(p, "model");
        MiniMindTranslator translator = new MiniMindTranslator();
        String reply1;
        try (ai.djl.inference.Predictor<String, String> predictor = model.newPredictor(translator)) {
            String response = predictor.predict(prompt);
            log.info("response: {}", response);
        }

        // ===== Test 3: 多轮对话（assistant 回复 + user 继续） =====
        log.info("\n=== Test 3: multi-turn (assistant reply + user follow-up) ===");
        try (ai.djl.inference.Predictor<String, String> predictor2 = model.newPredictor(translator)) {
            String turn1 = renderSimpleChatTemplate(
                    new String[][]{
                            {SYSTEM, "你是一个AI助手"},
                            {USER, "中国的首都是哪里？"},
                    }, true);
            reply1 = predictor2.predict(turn1);
            log.info("turn 1: {}", reply1);

            String turn2 = renderSimpleChatTemplate(
                    new String[][]{
                            {SYSTEM, "你是一个AI助手"},
                            {USER, "中国的首都是哪里？"},
                            {ASSISTANT, reply1},
                            {USER, "那个城市有什么著名景点？"},
                    }, true);
            String reply2 = predictor2.predict(turn2);
            log.info("turn 2: {}", reply2);
        } finally {
            model.close();
        }
    }

    /**
     * 简版 chat template（兼容 minimind-3 的 chat_template.jinja 最小集）：
     * <pre>
     * &lt;|im_start|&gt;system\n{system_msg}&lt;|im_end|&gt;\n
     * &lt;|im_start|&gt;user\n{user1}&lt;|im_end|&gt;\n
     * &lt;|im_start|&gt;assistant\n{assistant1}&lt;|im_end|&gt;\n
     * &lt;|im_start|&gt;user\n{user2}&lt;|im_end|&gt;\n
     * &lt;|im_start|&gt;assistant\n
     * </pre>
     */
    public static String renderSimpleChatTemplate(String[][] messages, boolean addGenerationPrompt) {
        StringBuilder sb = new StringBuilder();
        for (String[] msg : messages) {
            sb.append(IM_START).append(msg[0]).append('\n');
            sb.append(msg[1]).append(IM_END).append('\n');
        }
        if (addGenerationPrompt) {
            sb.append(IM_START).append(ASSISTANT).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析 minimind chat_template.jinja 中的简单变量替换：
     * 1. {%- if messages[0].role == 'system' %} → 把首条 system 直接前置
     * 2. 通用变量替换不展开（避免全实现 jinja）
     */
    public static String renderJinjaLike(String template, String system, String user) {
        String result = template;
        // 替换 tools 块（不展开）
        result = Pattern.compile("\\{%- if tools %\\}.*?\\{%- endif %\\}", Pattern.DOTALL)
                .matcher(result).replaceAll("");
        // 替换 messages 循环
        Pattern msgLoop = Pattern.compile(
                "\\{%- for message in messages %\\}(.*?)\\{%- endfor %\\}",
                Pattern.DOTALL);
        Matcher m = msgLoop.matcher(result);
        StringBuilder replaced = new StringBuilder();
        while (m.find()) {
            String block = m.group(1);
            // 简单替换 {message.role} 和 {message.content}
            block = block.replace("{{ message.role }}", USER).replace("{{ message.content }}", user);
            block = block.replace("{% if add_generation_prompt %}", "").replace("{% endif %}", "");
            m.appendReplacement(replaced, Matcher.quoteReplacement(block));
        }
        m.appendTail(replaced);
        return replaced.toString();
    }
}
