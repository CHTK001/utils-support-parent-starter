package com.chua.deeplearning.support.onnx.seq2seq;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T5 Seq2Seq ONNX 翻译器（ORT 原生，encoder-decoder 自回归生成）。
 * <p>
 * 模型文件获取：{@link Seq2SeqModelResources} 按<b>嵌入式 classpath jar 优先、modelscope downloadUrl 备选</b>
 * 加载 {@code encoder_model / decoder_model / decoder_with_past_model / tokenizer.json} 四个文件。
 * </p>
 * <p>
 * 生成流程：encoder 提取上下文隐状态 → decoder 首步（decoder 起始 token）→
 * decoder_with_past 循环自回归（携带过去 KV）→ 贪心解码，遇 EOS 或达到最大步数停止。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class T5Seq2SeqOrtTranslator implements ITranslator<String, String>, AutoCloseable {

    /**
     * 最大生成步数，防止死循环。
     */
    private static final int MAX_GENERATE_STEPS = 128;

    /**
     * 重复惩罚系数（贪心解码降重复）。
     */
    private static final float REPETITION_PENALTY = 1.9f;

    /**
     * 模型定义（t5-small）。
     */
    private final Seq2SeqModelDefinition def;

    /**
     * 任务前缀（如 "summarize: "），生成前拼接到输入；全局配置，空串表示不拼接。
     */
    private static volatile String taskPrefix = "";

    /**
     * ONNX 运行时环境。
     */
    private OrtEnvironment ortEnv;

    /**
     * 编码器会话。
     */
    private OrtSession encoderSession;

    /**
     * 解码器会话（首步）。
     */
    private OrtSession decoderSession;

    /**
     * 解码器历史会话（循环）。
     */
    private OrtSession decoderPastSession;

    /**
     * 分词器。
     */
    private HuggingFaceTokenizer tokenizer;

    /**
     * 是否已加载。
     */
    private volatile boolean loaded;

    /**
     * 无参构造，使用内置 t5-small 模型定义。
     */
    public T5Seq2SeqOrtTranslator() {
        this(Seq2SeqModelDefinition.T5_SMALL);
    }

    /**
     * 构造翻译器。
     *
     * @param def 模型定义
     */
    private T5Seq2SeqOrtTranslator(Seq2SeqModelDefinition def) {
        this.def = def;
    }

    /**
     * 设置任务前缀。
     *
     * @param prefix 任务前缀，如 "summarize: "、""（不拼接）
     */
    public static void setTaskPrefix(String prefix) {
        taskPrefix = prefix == null ? "" : prefix;
    }

    /**
     * 获取注册模型标识。
     *
     * @return t5-seq2seq
     */
    @Override
    public String name() {
        return def.modelId();
    }

    /**
     * 惰性加载模型与分词器。
     */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path modelDir = Seq2SeqModelResources.resolve(def);
        Path encoderPath = modelDir.resolve(def.requiredFiles().get(0));
        Path decoderPath = modelDir.resolve(def.requiredFiles().get(1));
        Path decoderPastPath = modelDir.resolve(def.requiredFiles().get(2));
        Path tokenizerPath = modelDir.resolve(def.requiredFiles().get(3));
        for (Path p : List.of(encoderPath, decoderPath, decoderPastPath, tokenizerPath)) {
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("[t5-seq2seq] 模型资源缺失: " + p);
            }
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
        log.debug("[t5-seq2seq] encoder inputs={} outputs={}", encoderSession.getInputNames(), encoderSession.getOutputNames());
        log.debug("[t5-seq2seq] decoder inputs={} outputs={}", decoderSession.getInputNames(), decoderSession.getOutputNames());
        log.debug("[t5-seq2seq] decoder_past inputs={} outputs={}", decoderPastSession.getInputNames(), decoderPastSession.getOutputNames());
        log.info("[t5-seq2seq] 模型加载完成: encoder={} decoder={} decoder_past={}",
                encoderPath.getFileName(), decoderPath.getFileName(), decoderPastPath.getFileName());
        loaded = true;
    }

    /**
     * 执行文本生成。
     *
     * @param text 输入文本（可含任务前缀，若未配置将由调用方拼接）
     * @return 生成结果
     */
    @Override
    public String translate(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            prepare();
            String input = taskPrefix.isBlank() ? text : taskPrefix + text;
            Encoding encoding = tokenizer.encode(input);
            long[] sourceIds = encoding.getIds();
            long[] sourceMask = new long[sourceIds.length];
            for (int i = 0; i < sourceIds.length; i++) {
                sourceMask[i] = 1L;
            }
            if (sourceIds.length == 0) {
                return "";
            }

            float[] encoderHidden = runEncoder(sourceIds, sourceMask);
            List<Long> generated = runDecoder(sourceIds, sourceMask, encoderHidden);

            if (generated.isEmpty()) {
                return "";
            }
            long[] tokenIds = new long[generated.size()];
            for (int i = 0; i < generated.size(); i++) {
                tokenIds[i] = generated.get(i);
            }
            return tokenizer.decode(tokenIds).trim();
        } catch (Exception e) {
            throw new RuntimeException("[t5-seq2seq] 文本生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 运行编码器，得到上下文隐状态。
     *
     * @param sourceIds   输入 token id
     * @param sourceMask  输入注意力掩码
     * @return 编码器隐藏状态（[srcLen * dModel]）
     * @throws Exception ORT 异常
     */
    private float[] runEncoder(long[] sourceIds, long[] sourceMask) throws Exception {
        try (OnnxTensor tIds = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(sourceIds), new long[]{1, sourceIds.length});
             OnnxTensor tMask = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(sourceMask), new long[]{1, sourceIds.length});
             OrtSession.Result r = encoderSession.run(Map.of("input_ids", tIds, "attention_mask", tMask))) {
            OnnxTensor encOut = (OnnxTensor) r.get("last_hidden_state").orElse(r.iterator().next().getValue());
            return encOut.getFloatBuffer().array().clone();
        }
    }

    /**
     * 贪心自回归生成。
     *
     * @param sourceIds     输入 token id
     * @param sourceMask    输入注意力掩码
     * @param encoderHidden 编码器隐藏状态
     * @return 生成的 token id 列表
     * @throws Exception ORT 异常
     */
    private List<Long> runDecoder(long[] sourceIds, long[] sourceMask, float[] encoderHidden) throws Exception {
        List<Long> generated = new ArrayList<>();
        Set<String> pastInputs = decoderPastSession.getInputNames();
        int srcLen = sourceIds.length;

        // 首步：decoder 起始 token + 编码器上下文
        Map<String, OnnxTensor> feed = new HashMap<>();
        feed.put("input_ids", OnnxTensor.createTensor(ortEnv,
                LongBuffer.wrap(new long[]{def.decoderStartId()}), new long[]{1, 1}));
        feed.put("encoder_attention_mask", OnnxTensor.createTensor(ortEnv,
                LongBuffer.wrap(sourceMask), new long[]{1, srcLen}));
        if (decoderSession.getInputNames().contains("encoder_hidden_states")) {
            feed.put("encoder_hidden_states", OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(encoderHidden), new long[]{1, srcLen, encoderHidden.length / srcLen}));
        }
        int decSeq;
        try (OrtSession.Result first = decoderSession.run(feed)) {
            long next = argmax(first, generated);
            if (next == def.eosId() || next == def.decoderStartId()) {
                return generated;
            }
            generated.add(next);
            float[][][] dKv = new float[def.numLayers()][2][];
            float[][][] eKv = new float[def.numLayers()][2][];
            boolean hasEEncoder = first.get("present.0.encoder.key").isPresent();
            long[] eKvShape = null;
            for (int i = 0; i < def.numLayers(); i++) {
                dKv[i][0] = tensorData(first, "present." + i + ".decoder.key");
                dKv[i][1] = tensorData(first, "present." + i + ".decoder.value");
                if (hasEEncoder) {
                    eKv[i][0] = tensorData(first, "present." + i + ".encoder.key");
                    eKv[i][1] = tensorData(first, "present." + i + ".encoder.value");
                }
            }
            if (hasEEncoder) {
                eKvShape = ((OnnxTensor) first.get("present.0.encoder.key").get()).getInfo().getShape();
            }
            decSeq = (int) ((OnnxTensor) first.get("present.0.decoder.key").get()).getInfo().getShape()[2];

            // 循环：decoder_with_past 自回归生成
            for (int step = 0; step < MAX_GENERATE_STEPS; step++) {
                Map<String, OnnxTensor> feedLoop = new HashMap<>();
                if (pastInputs.contains("input_ids")) {
                    feedLoop.put("input_ids", OnnxTensor.createTensor(ortEnv,
                            LongBuffer.wrap(new long[]{next}), new long[]{1, 1}));
                }
                if (pastInputs.contains("encoder_attention_mask")) {
                    feedLoop.put("encoder_attention_mask", OnnxTensor.createTensor(ortEnv,
                            LongBuffer.wrap(sourceMask), new long[]{1, srcLen}));
                }
                if (pastInputs.contains("encoder_hidden_states")) {
                    feedLoop.put("encoder_hidden_states", OnnxTensor.createTensor(ortEnv,
                            FloatBuffer.wrap(encoderHidden), new long[]{1, srcLen, encoderHidden.length / srcLen}));
                }
                long[] decShape = {1, def.numHeads(), decSeq, def.headDim()};
                for (int layer = 0; layer < def.numLayers(); layer++) {
                    String dk = "past_key_values." + layer + ".decoder.key";
                    String dv = "past_key_values." + layer + ".decoder.value";
                    if (pastInputs.contains(dk)) {
                        feedLoop.put(dk, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(dKv[layer][0]), decShape));
                    }
                    if (pastInputs.contains(dv)) {
                        feedLoop.put(dv, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(dKv[layer][1]), decShape));
                    }
                    String ek = "past_key_values." + layer + ".encoder.key";
                    String ev = "past_key_values." + layer + ".encoder.value";
                    if (hasEEncoder && pastInputs.contains(ek)) {
                        feedLoop.put(ek, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(eKv[layer][0]), eKvShape));
                    }
                    if (hasEEncoder && pastInputs.contains(ev)) {
                        feedLoop.put(ev, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(eKv[layer][1]), eKvShape));
                    }
                }
                try (OrtSession.Result loopResult = decoderPastSession.run(feedLoop)) {
                    next = argmax(loopResult, generated);
                    for (int layer = 0; layer < def.numLayers(); layer++) {
                        dKv[layer][0] = tensorData(loopResult, "present." + layer + ".decoder.key");
                        dKv[layer][1] = tensorData(loopResult, "present." + layer + ".decoder.value");
                    }
                    // 注：decoder_with_past 仅输出 decoder 侧 KV，编码器侧 KV 保持首步的 eKv 不变
                }
                decSeq++;
                if (next == def.eosId() || next == def.decoderStartId()) {
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
        return generated;
    }

    /**
     * 从结果中取张量 float 数据。
     *
     * @param result 推理结果
     * @param name   输出名
     * @return float 数组
     * @throws Exception ORT 异常
     */
    private float[] tensorData(OrtSession.Result result, String name) throws Exception {
        return ((OnnxTensor) result.get(name).get()).getFloatBuffer().array().clone();
    }

    /**
     * 从 logits 取 argmax（含重复惩罚 + 禁止 decoder 起始 token）。
     *
     * @param result 推理结果
     * @param gen    已生成 token
     * @return 下一个 token id
     * @throws Exception ORT 异常
     */
    private long argmax(OrtSession.Result result, List<Long> gen) throws Exception {
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
        row[(int) def.decoderStartId()] = Float.NEGATIVE_INFINITY;
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

    /**
     * 关闭会话与分词器。
     */
    @Override
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