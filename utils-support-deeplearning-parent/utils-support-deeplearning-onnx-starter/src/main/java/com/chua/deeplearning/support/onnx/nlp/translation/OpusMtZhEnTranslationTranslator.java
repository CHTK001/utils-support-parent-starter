package com.chua.deeplearning.support.onnx.nlp.translation;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
* opus-mt-zh-en 中译英机器翻译（marianmt，ORT 原生 + huggingface Tokenizer）。
*
* <p>模型由 jar {@code utils-support-models-onnx-opus-mt-zh-en} 提供，资源在
* {@code nlp/translation/opus_mt_zh_en/} 下。Marian 为 encoder-decoder 自回归架构：
* <ol>
*   <li>encoder_model：{@code input_ids + attention_mask} → {@code last_hidden_state}</li>
*   <li>decoder_model（首步）：{@code encoder_hidden_states + decoder_start(65000)} → logits + present KV</li>
*   <li>decoder_with_past_model（循环）：{@code input_ids + past_key_values} → logits + present KV</li>
*   <li>贪心解码 + repetition penalty，遇 EOS(0)/候选分隔符(15) 停止，取第一个候选</li>
* </ol>
* tokenizer.json 的 Precompiled chars映射 为空导致 Rust tokenizers 崩溃，已替换为 NFKC。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class OpusMtZhEnTranslationTranslator implements ITranslator<String, String>, AutoCloseable {

    /**
    * 解码起始 令牌（= pad 标识），Marian 固定。
     */
    private static final long DECODER_START_ID = 65000L;

    /**
    * EOS 令牌 标识（Marian 固定为 0）。
     */
    private static final long EOS_ID = 0L;

    /**
    * 候选翻译分隔符 "-" 的 令牌 标识，生成到它即取第一个候选。
     */
    private static final long SEPARATOR_ID = 15L;

    /**
    * 解码器层数（marian-基础 固定 6）。
     */
    private static final int NUM_LAYERS = 6;

    /**
    * 注意力头数（marian-基础 d_模型=512 / 64）。
     */
    private static final int NUM_HEADS = 8;

    /**
    * 单头维度。
     */
    private static final int HEAD_DIM = 64;

    /**
    * 最大生成步数，防止死循环。
     */
    private static final int MAX_GENERATE_STEPS = 80;

    /**
    * 重复惩罚系数（贪心解码降重复）。
     */
    private static final float REPETITION_PENALTY = 2.2f;

    /**
    * jar 内资源根目录。
     */
    private static final String RESOURCE_BASE = "nlp/translation/opus_mt_zh_en/";

    /**
    * 编码器 模型文件名。
     */
    private static final String ENCODER_FILE = "encoder_model_quantized.onnx";

    /**
    * 解码器（首步）模型文件名。
     */
    private static final String DECODER_FILE = "decoder_model_quantized.onnx";

    /**
    * 解码器（带缓存）模型文件名。
     */
    private static final String DECODER_PAST_FILE = "decoder_with_past_model_quantized.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 编码器会话 */
    private OrtSession encoderSession;
    /** 解码器会话 */
    private OrtSession decoderSession;
    /** 解码器历史会话 */
    /** 解码器past会话 */
    private OrtSession decoderPastSession;
    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;
    /** 是否已加载 */
    private volatile boolean loaded;

    /**
    * 懒加载模型与 tokenizer。
     */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("opus-mt-zh-en-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("opus_mt_zh_en");
        Files.createDirectories(modelDir);
        NativeLoader.of("opus-mt-zh-en")
                .from(OpusMtZhEnTranslationTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path encoderPath = modelDir.resolve(ENCODER_FILE);
        Path decoderPath = modelDir.resolve(DECODER_FILE);
        Path decoderPastPath = modelDir.resolve(DECODER_PAST_FILE);
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        if (!Files.isRegularFile(encoderPath) || !Files.isRegularFile(decoderPath)
                || !Files.isRegularFile(decoderPastPath) || !Files.isRegularFile(tokenizerPath)) {
            throw new IllegalArgumentException("opus-mt-zh-en 模型资源缺失: " + modelDir);
        }
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        this.encoderSession = ortEnv.createSession(encoderPath.toString(), opts);
        this.decoderSession = ortEnv.createSession(decoderPath.toString(), opts);
        this.decoderPastSession = ortEnv.createSession(decoderPastPath.toString(), opts);
        this.tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(false)
                .build();
        log.info("[opus-mt-zh-en] 模型加载完成: encoder={} decoder={} decoder_past={}", encoderPath.getFileName(), decoderPath.getFileName(), decoderPastPath.getFileName());
        loaded = true;
    }

    @Override
    /** 名称 */
    public String name() {
        return "opus-mt-zh-en";
    }

    @Override
    /** Translate */
    public String translate(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            prepare();
            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] attn = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                attn[i] = 1L;
            }

            // 1. encoder: input_ids + attention_mask -> last_hidden_state
            float[] encoderHidden;
            try (OnnxTensor tIds = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(ids), new long[]{1, ids.length});
                 OnnxTensor tAttn = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(attn), new long[]{1, ids.length});
                 OrtSession.Result r = encoderSession.run(Map.of("input_ids", tIds, "attention_mask", tAttn))) {
                OnnxTensor encOut = (OnnxTensor) r.get("last_hidden_state").get();
                encoderHidden = encOut.getFloatBuffer().array().clone();
            }

            List<Long> generated = new ArrayList<>();
            long[] encMask = attn;
            int decSeq;

 // 2. 解码器 首步：解码器_启动_令牌 + 编码器_hidden_状态
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("input_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(new long[]{DECODER_START_ID}), new long[]{1, 1}));
            feed.put("encoder_attention_mask", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(encMask), new long[]{1, ids.length}));
            feed.put("encoder_hidden_states", OnnxTensor.createTensor(ortEnv,
                    java.nio.FloatBuffer.wrap(encoderHidden), new long[]{1, ids.length, 512}));
            try (OrtSession.Result first = decoderSession.run(feed)) {
                long next = argmax(first, generated);
                if (next == EOS_ID) {
                    return "";
                }
                generated.add(next);
                float[][][] dKv = new float[NUM_LAYERS][2][];
                float[][][] eKv = new float[NUM_LAYERS][2][];
                for (int i = 0; i < NUM_LAYERS; i++) {
                    dKv[i][0] = ((OnnxTensor) first.get("present." + i + ".decoder.key").get()).getFloatBuffer().array().clone();
                    dKv[i][1] = ((OnnxTensor) first.get("present." + i + ".decoder.value").get()).getFloatBuffer().array().clone();
                    eKv[i][0] = ((OnnxTensor) first.get("present." + i + ".encoder.key").get()).getFloatBuffer().array().clone();
                    eKv[i][1] = ((OnnxTensor) first.get("present." + i + ".encoder.value").get()).getFloatBuffer().array().clone();
                }
                long[] eKvShape = ((OnnxTensor) first.get("present.0.encoder.key").get()).getInfo().getShape();
                decSeq = (int) ((OnnxTensor) first.get("present.0.decoder.key").get()).getInfo().getShape()[2];

 // 3. 解码器_with_past 循环：自回归生成
                for (int step = 0; step < MAX_GENERATE_STEPS; step++) {
                    Map<String, OnnxTensor> f2 = new HashMap<>();
                    f2.put("input_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(new long[]{next}), new long[]{1, 1}));
                    f2.put("encoder_attention_mask", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(encMask), new long[]{1, ids.length}));
                    long[] dShape = {1, NUM_HEADS, decSeq, HEAD_DIM};
                    for (int layer = 0; layer < NUM_LAYERS; layer++) {
                        f2.put("past_key_values." + layer + ".decoder.key",
                                OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(dKv[layer][0]), dShape));
                        f2.put("past_key_values." + layer + ".decoder.value",
                                OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(dKv[layer][1]), dShape));
                        f2.put("past_key_values." + layer + ".encoder.key",
                                OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(eKv[layer][0]), eKvShape));
                        f2.put("past_key_values." + layer + ".encoder.value",
                                OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(eKv[layer][1]), eKvShape));
                    }
                    try (OrtSession.Result stepResult = decoderPastSession.run(f2)) {
                        next = argmax(stepResult, generated);
                        for (int layer = 0; layer < NUM_LAYERS; layer++) {
                            dKv[layer][0] = ((OnnxTensor) stepResult.get("present." + layer + ".decoder.key").get()).getFloatBuffer().array().clone();
                            dKv[layer][1] = ((OnnxTensor) stepResult.get("present." + layer + ".decoder.value").get()).getFloatBuffer().array().clone();
                        }
                    }
                    decSeq++;
                    if (next == EOS_ID || next == DECODER_START_ID || next == SEPARATOR_ID) {
                        break;
                    }
                    generated.add(next);
                    if (generated.size() >= 3
                            && generated.get(generated.size() - 1).equals(generated.get(generated.size() - 2))
                            && generated.get(generated.size() - 2).equals(generated.get(generated.size() - 3))) {
                        break;
                    }
                }
            }

            long[] tokenIds = new long[generated.size()];
            for (int i = 0; i < generated.size(); i++) {
                tokenIds[i] = generated.get(i);
            }
            String decoded = tokenizer.decode(tokenIds).trim();
            if (Boolean.getBoolean("opus.debug")) {
                log.info("[opus-mt-zh-en] raw decoded: {} | ids: {}", decoded, java.util.Arrays.toString(tokenIds));
            }
            return postProcess(decoded);
        } catch (Exception e) {
            throw new RuntimeException("[opus-mt-zh-en] 中译英失败: " + e.getMessage(), e);
        }
    }

    /**
    * 后处理：截取第一个完整翻译候选。
    *
    * <p>Marian 贪心解码会生成多个候选（以 " - " 分隔）或附加冗余尾巴
    * （如 " (标志) ..."）。策略：优先取 " - " 前；否则取第一个句号/感叹号后的
    * 完整句（保留标点），丢弃剩余尾巴。</p>
    *
    * @param decoded 原始解码文本
    * @return 清洗后的译文
     */
    private static String postProcess(String decoded) {
        if (decoded == null || decoded.isEmpty()) {
            return decoded;
        }
        int sep = decoded.indexOf(" - ");
        if (sep > 0) {
            return decoded.substring(0, sep).trim();
        }
        int end = decoded.length();
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < decoded.length()
                    && (Character.isWhitespace(decoded.charAt(i + 1)) || decoded.charAt(i + 1) == ')'
                    || decoded.charAt(i + 1) == '\u2014' || decoded.charAt(i + 1) == '-')) {
                end = i + 1;
                break;
            }
        }
        return decoded.substring(0, end).trim();
    }

    /**
    * 从 logits 取 argmax（含重复惩罚 + 禁止 pad）。
    *
    * @param result ORT 推理结果
    * @param gen    已生成 令牌
    * @return 下一 令牌 标识
     */
    private static long argmax(OrtSession.Result result, List<Long> gen) throws Exception {
        OnnxTensor logitsTensor = (OnnxTensor) result.get("logits").get();
        float[][][] logits = (float[][][]) logitsTensor.getValue();
        float[] row = logits[0][0];
        Set<Long> seen = new HashSet<>(gen);
        for (Long token : seen) {
            int idx = token.intValue();
            if (idx < 0 || idx >= row.length) {
                continue;
            }
            float value = row[idx];
            row[idx] = value < 0 ? value * REPETITION_PENALTY : value / REPETITION_PENALTY;
        }
        row[(int) DECODER_START_ID] = Float.NEGATIVE_INFINITY;
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 1; i < row.length; i++) {
            if (row[i] > bestScore) {
                bestScore = row[i];
                best = i;
            }
        }
        return best;
    }

    @Override
    /** 关闭 */
    public void close() {
        for (OrtSession session : List.of(encoderSession, decoderSession, decoderPastSession)) {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
        encoderSession = null;
        decoderSession = null;
        decoderPastSession = null;
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        loaded = false;
    }
}