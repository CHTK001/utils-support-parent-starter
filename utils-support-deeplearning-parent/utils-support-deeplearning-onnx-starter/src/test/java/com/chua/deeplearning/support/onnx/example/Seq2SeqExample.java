package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.nlp.TextTranslator;
import com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqOrtTranslator;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * Seq2Seq 文本生成能力示例。
 *
 * <p>覆盖三类 seq2seq 模型：</p>
 * <ul>
 *   <li><b>嵌入式优先</b>：{@code opus-mt-zh-en}（中译英，模型 jar 内嵌，经 {@link TextTranslator} 门面）；</li>
 *   <li><b>modelscope 下载</b>：{@code t5-seq2seq}（T5-small 英文摘要/生成/翻译）；</li>
 *   <li><b>modelscope 下载（中文）</b>：{@code mt5-seq2seq}（mT5-small 中文多句 → 一句总结）。</li>
 * </ul>
 *
 * <p><b>输出 token 大小控制</b>：摘要/生成命令可在任务后加一个纯数字参数指定 <i>最大生成 token 数</i>
 * （默认 128，最小 10）；也可用 {@code -D} 或代码里 {@link T5Seq2SeqOrtTranslator#setMaxNewTokens} /
 * {@link T5Seq2SeqOrtTranslator#setMinNewTokens} 调整。</p>
 *
 * <pre>{@code
 *   Seq2SeqExample list                                              # 列出 seq2seq 模型
 *   Seq2SeqExample opus "你好，世界"                                   # 嵌入式中译英（MarianMT seq2seq）
 *   Seq2SeqExample t5 summarize <长文本>                              # T5 英文摘要（默认 128 token）
 *   Seq2SeqExample t5 summarize 50 <长文本>                           # T5 英文摘要，最多 50 token
 *   Seq2SeqExample t5 translate English to Chinese <text>            # T5 翻译
 *   Seq2SeqExample t5 <文本>                                          # T5 文本生成（无任务前缀）
 *   Seq2SeqExample mt5 summarize 30 <中文多句文本>                    # 中文 多句→一句，最多 30 token
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class Seq2SeqExample extends ExampleBase {

    /**
     * 构造工具类。
     */
    private Seq2SeqExample() {
    }

    /**
     * 主入口。
     *
     * @param args 命令行参数
     * @throws Exception 执行异常
     */
    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : null;

        if (model == null || "list".equalsIgnoreCase(model)) {
            printModels("seq2seq", "onnx", List.of(
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("opus-mt-zh-en").description("嵌入式 中译英 (MarianMT seq2seq)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("t5-seq2seq").description("T5-small 摘要/生成/翻译 (modelscope 下载)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("mt5-seq2seq").description("mT5-small 中文摘要 多句→一句 (modelscope 下载)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("bart-seq2seq").description("BART-large-cnn 摘要 (待适配)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("chinese-t5-base").description("中文 T5-base 摘要 (待适配)").build(),
                    com.chua.common.support.ai.chat.ModelDefinition.builder()
                            .id("randeng-t5").description("中文 Randeng-T5 生成 (待适配)").build()));
            return;
        }

        String text = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;

        if ("opus".equalsIgnoreCase(model)) {
            runOpus(text);
            return;
        }
        if ("t5".equalsIgnoreCase(model)) {
            runSeq2Seq("t5-seq2seq", "t5", args, text);
            return;
        }
        if ("mt5".equalsIgnoreCase(model)) {
            runSeq2Seq("mt5-seq2seq", "mt5", args, text);
            return;
        }
        System.out.println("未知模型: " + model);
    }

    /**
     * 嵌入式中译英（MarianMT，模型 jar 内嵌）。
     *
     * @param text 中文文本
     */
    private static void runOpus(String text) {
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
    }

    /**
     * 运行 T5 / mT5 seq2seq 生成。
     *
     * <p>命令格式：{@code t5|mt5 summarize [maxTokens] <文本>} / {@code t5 translate X to Y <text>} /
     * {@code t5|mt5 <文本>}。maxTokens 为任务后的第一个纯数字参数。</p>
     *
     * @param modelId 注册的模型标识
     * @param label   打印前缀
     * @param args    命令行参数
     * @param text    拼接后的原始文本
     */
    private static void runSeq2Seq(String modelId, String label, String[] args, String text) {
        if (text == null) {
            System.out.println("[" + label + "] 需要生成文本");
            return;
        }
        String task = args[1];
        String input;
        String prefix;
        int maxTokens = -1;
        if ("summarize".equalsIgnoreCase(task)) {
            // 摘要任务：可带可选 maxTokens 数字参数
            prefix = "summarize: ";
            int contentStart = 2;
            if (args.length > 3 && isInteger(args[2])) {
                maxTokens = Integer.parseInt(args[2]);
                contentStart = 3;
            }
            input = String.join(" ", java.util.Arrays.copyOfRange(args, contentStart, args.length));
        } else if ("translate".equalsIgnoreCase(task) && args.length > 5 && "to".equalsIgnoreCase(args[3])) {
            // 翻译任务：t5 translate English to Chinese <text>
            input = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
            prefix = "translate " + args[2] + " to " + args[4] + ": ";
        } else {
            // 默认生成任务（不拼接前缀）
            input = text;
            prefix = "";
        }

        T5Seq2SeqOrtTranslator.setTaskPrefix(prefix);
        ModelRegistry.discoverAll();
        long t0 = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        ITranslator<String, String> tr = (ITranslator<String, String>) (ITranslator<?, ?>)
                ModelRegistry.createTranslator(modelId, null);
        if (tr instanceof T5Seq2SeqOrtTranslator ort) {
            if (maxTokens > 0) {
                ort.setMaxNewTokens(maxTokens);
            }
        }
        String output = tr.translate(input);
        System.out.println("[" + label + "] task=" + (prefix.isEmpty() ? "generate" : prefix.trim())
                + " maxTokens=" + (maxTokens > 0 ? maxTokens : 128));
        System.out.println("      输入: " + input);
        System.out.println("      输出: " + output);
        printResult(modelId, "onnx", modelId, t0);
    }

    /**
     * 判断字符串是否为整数。
     *
     * @param s 字符串
     * @return true 表示整数
     */
    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}