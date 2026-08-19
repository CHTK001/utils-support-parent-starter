package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.nlp.TextTranslator;
import com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqOrtTranslator;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * Seq2Seq 文本生成能力示例。
 *
 * <p>验证两类 seq2seq 模型的完整链路：</p>
 * <ul>
 *   <li><b>嵌入式优先</b>：{@code opus-mt-zh-en}（中译英，模型 jar 内嵌，经 {@link TextTranslator} 门面）；</li>
 *   <li><b>modelscope 下载</b>：{@code t5-seq2seq}（T5-small 摘要/生成，经 {@link ModelRegistry} 懒加载）。</li>
 * </ul>
 *
 * <pre>{@code
 *   Seq2SeqExample list                       # 列出 seq2seq 相关模型
 *   Seq2SeqExample opus "你好，世界"            # 嵌入式中译英
 *   Seq2SeqExample t5 summarize <长文本>       # T5 摘要（自动下载模型）
 *   Seq2SeqExample t5 <文本>                   # T5 文本生成（不拼接任务前缀）
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class Seq2SeqExample extends ExampleBase {

    private Seq2SeqExample() {
    }

    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : null;

        if (model == null || "list".equalsIgnoreCase(model)) {
            printModels("seq2seq", "onnx", List.of(
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("opus-mt-zh-en").description("嵌入式 中译英 (MarianMT)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("t5-seq2seq").description("T5-small 摘要/生成 (modelscope 下载)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("bart-seq2seq").description("BART-large-cnn 摘要 (自动下载)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("chinese-t5-base").description("中文 T5-base 摘要").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("randeng-t5").description("中文 Randeng-T5 生成").build()));
            return;
        }

        String text = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;

        if ("opus".equalsIgnoreCase(model)) {
            if (text == null) {
                System.out.println("[opus] 需要翻译文本");
                return;
            }
            long t0 = System.currentTimeMillis();
            TextTranslator translator = TextTranslator.create("opus-mt-zh-en");
            String translated = translator.translate(text);
            System.out.println("[opus] 原文: " + text);
            System.out.println("      译文: " + translated);
            printResult("opus-mt-zh-en", "onnx", "embedded", t0);
            return;
        }

        if ("t5".equalsIgnoreCase(model)) {
            if (text == null) {
                System.out.println("[t5] 需要生成文本");
                return;
            }
            boolean summarize = args[1] != null && "summarize".equalsIgnoreCase(args[1])
                    && args.length > 2;
            if (summarize) {
                text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                T5Seq2SeqOrtTranslator.setTaskPrefix("summarize: ");
            } else {
                T5Seq2SeqOrtTranslator.setTaskPrefix("");
            }
            ModelRegistry.discoverAll();
            long t0 = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            ITranslator<String, String> t5 = (ITranslator<String, String>) (ITranslator<?, ?>)
                    ModelRegistry.createTranslator("t5-seq2seq", null);
            String output = t5.translate(text);
            System.out.println("[t5]" + (summarize ? " summarize" : "") + " 输入: " + text);
            System.out.println("      输出: " + output);
            printResult("t5-seq2seq", "onnx", "t5-small", t0);
            return;
        }

        System.out.println("未知模型: " + model);
    }
}