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
     * 重复惩罚系数（贪心解码降重复，过大会迫使选次优 token 或提前截止）。
     */
    private static final float REPETITION_PENALTY = 1.3f;

    /**
     * 默认最小生成 token 数（达到前不停止，避免摘要过短）。
     */
    private static final int DEFAULT_MIN_NEW_TOKENS = 10;

    /**
     * 默认最大生成 token 数（防止死循环）。
     */
    private static final int DEFAULT_MAX_NEW_TOKENS = 128;

    /**
     * 模型定义（t5-small）。
     */
    private final Seq2SeqModelDefinition def;

    /**
     * 最小生成 token 数。
     */
    private int minNewTokens = DEFAULT_MIN_NEW_TOKENS;

    /**
     * 最大生成 token 数。
     */
    private int maxNewTokens = DEFAULT_MAX_NEW_TOKENS;

    /**
     * 束搜索宽度；1 表示贪心解码。
     */
    private int numBeams = 1;

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
    protected T5Seq2SeqOrtTranslator(Seq2SeqModelDefinition def) {
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
     * 设置最小生成 token 数（达到前不会因 EOS 提前停止）。
     *
     * @param min 最小 token 数，小于 0 视为 0
     */
    public void setMinNewTokens(int min) {
        this.minNewTokens = Math.max(0, min);
    }

    /**
     * 设置最大生成 token 数（超过后强制停止，防止死循环）。
     *
     * @param max 最大 token 数，小于 1 视为 1
     */
    public void setMaxNewTokens(int max) {
        this.maxNewTokens = Math.max(1, max);
    }

    /**
     * 设置束搜索宽度；1 表示贪心解码。
     *
     * @param beams 束宽，小于 1 视为 1
     */
    public void setNumBeams(int beams) {
        this.numBeams = Math.max(1, beams);
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
            List<Long> generated = numBeams > 1
                    ? runDecoderBeam(sourceIds, sourceMask, encoderHidden)
                    : runDecoder(sourceIds, sourceMask, encoderHidden);

            if (generated.isEmpty()) {
                return "";
            }
            long[] tokenIds = new long[generated.size()];
            for (int i = 0; i < generated.size(); i++) {
                tokenIds[i] = generated.get(i);
            }
            return postProcess(tokenizer.decode(tokenIds));
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
            long next = argmax(first, generated, generated.size() >= minNewTokens);
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
            // 注意力头数/单头维度/序列长度从输出维度动态解析（兼容 t5 / mt5 等不同头数配置）
            long[] d0Shape = ((OnnxTensor) first.get("present.0.decoder.key").get()).getInfo().getShape();
            int heads = (int) d0Shape[1];
            int headDim = (int) d0Shape[3];
            decSeq = (int) d0Shape[2];

            // 循环：decoder_with_past 自回归生成
            for (int step = 0; step < maxNewTokens; step++) {
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
                long[] decShape = {1, heads, decSeq, headDim};
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
                    next = argmax(loopResult, generated, generated.size() >= minNewTokens);
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
     * 从 logits 取 argmax（含重复惩罚 + 禁止 decoder 起始 token + 可选禁用 EOS）。
     *
     * @param result   推理结果
     * @param gen      已生成 token
     * @param allowEos 是否允许生成 EOS（false 表示达到最小长度前停止）
     * @return 下一个 token id
     * @throws Exception ORT 异常
     */
    private long argmax(OrtSession.Result result, List<Long> gen, boolean allowEos) throws Exception {
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
        if (!allowEos) {
            row[(int) def.eosId()] = Float.NEGATIVE_INFINITY;
        }
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
     * 后处理：剔除 T5/mT5 的占位特殊 token（如 &lt;extra_id_0&gt;）并清理空白。
     *
     * @param decoded 原始解码文本
     * @return 清洗后的文本
     */
    private static String postProcess(String decoded) {
        if (decoded == null || decoded.isEmpty()) {
            return decoded;
        }
        return decoded.replaceAll("<extra_id_\\d+>", "").trim();
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

    /**
     * 束搜索自回归生成（numBeams &gt; 1 时启用）。
     * <p>维护多个候选假设，每步对每个候选独立扩展并保留分数最高的 numBeams 个，
     * 显著改善小模型的摘要/生成质量（中文效果提升明显）。</p>
     *
     * @param sourceIds     输入 token id
     * @param sourceMask    输入注意力掩码
     * @param encoderHidden 编码器隐藏状态
     * @return 最优假设的 token id 列表
     * @throws Exception ORT 异常
     */
    private List<Long> runDecoderBeam(long[] sourceIds, long[] sourceMask, float[] encoderHidden) throws Exception {
        int srcLen = sourceIds.length;
        Set<String> pastInputs = decoderPastSession.getInputNames();

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
        float[] firstRow;
        float[][][] firstDKv;
        float[][][] firstEKv;
        long[] eKvShape;
        int heads;
        int headDim;
        int baseDecSeq;
        try (OrtSession.Result first = decoderSession.run(feed)) {
            OnnxTensor lt = (OnnxTensor) first.get("logits").get();
            firstRow = ((float[][][]) lt.getValue())[0][0].clone();
            firstDKv = new float[def.numLayers()][2][];
            firstEKv = new float[def.numLayers()][2][];
            boolean hasE = first.get("present.0.encoder.key").isPresent();
            long[] d0 = ((OnnxTensor) first.get("present.0.decoder.key").get()).getInfo().getShape();
            heads = (int) d0[1];
            headDim = (int) d0[3];
            baseDecSeq = (int) d0[2];
            for (int i = 0; i < def.numLayers(); i++) {
                firstDKv[i][0] = tensorData(first, "present." + i + ".decoder.key");
                firstDKv[i][1] = tensorData(first, "present." + i + ".decoder.value");
                if (hasE) {
                    firstEKv[i][0] = tensorData(first, "present." + i + ".encoder.key");
                    firstEKv[i][1] = tensorData(first, "present." + i + ".encoder.value");
                }
            }
            eKvShape = hasE
                    ? ((OnnxTensor) first.get("present.0.encoder.key").get()).getInfo().getShape()
                    : null;
        }

        // 初始化 numBeams 个候选（各自持有独立 KV 副本）
        List<Beam> beams = new ArrayList<>();
        for (long tok : topKTokens(firstRow, numBeams, List.of(), false)) {
            List<Long> ids = new ArrayList<>();
            ids.add(tok);
            beams.add(new Beam(ids, firstRow[(int) tok], cloneKv(firstDKv), baseDecSeq + 1, false));
        }
        if (beams.isEmpty()) {
            return List.of();
        }

        List<Beam> finished = new ArrayList<>();
        for (int step = 0; step < maxNewTokens && !beams.isEmpty(); step++) {
            List<Beam> next = new ArrayList<>();
            for (Beam b : beams) {
                long last = b.ids.get(b.ids.size() - 1);
                Map<String, OnnxTensor> f = new HashMap<>();
                f.put("input_ids", OnnxTensor.createTensor(ortEnv,
                        LongBuffer.wrap(new long[]{last}), new long[]{1, 1}));
                f.put("encoder_attention_mask", OnnxTensor.createTensor(ortEnv,
                        LongBuffer.wrap(sourceMask), new long[]{1, srcLen}));
                if (pastInputs.contains("encoder_hidden_states")) {
                    f.put("encoder_hidden_states", OnnxTensor.createTensor(ortEnv,
                            FloatBuffer.wrap(encoderHidden), new long[]{1, srcLen, encoderHidden.length / srcLen}));
                }
                long[] decShape = {1, heads, b.decSeq, headDim};
                for (int l = 0; l < def.numLayers(); l++) {
                    String dk = "past_key_values." + l + ".decoder.key";
                    String dv = "past_key_values." + l + ".decoder.value";
                    if (pastInputs.contains(dk)) {
                        f.put(dk, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(b.dKv[l][0]), decShape));
                    }
                    if (pastInputs.contains(dv)) {
                        f.put(dv, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(b.dKv[l][1]), decShape));
                    }
                    String ek = "past_key_values." + l + ".encoder.key";
                    String ev = "past_key_values." + l + ".encoder.value";
                    if (eKvShape != null && pastInputs.contains(ek)) {
                        f.put(ek, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(firstEKv[l][0]), eKvShape));
                    }
                    if (eKvShape != null && pastInputs.contains(ev)) {
                        f.put(ev, OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(firstEKv[l][1]), eKvShape));
                    }
                }
                try (OrtSession.Result r = decoderPastSession.run(f)) {
                    OnnxTensor lt = (OnnxTensor) r.get("logits").get();
                    float[] row = ((float[][][]) lt.getValue())[0][0].clone();
                    boolean allowEos = b.ids.size() >= minNewTokens;
                    for (long tok : topKTokens(row, numBeams, b.ids, allowEos)) {
                        float score = b.score + row[(int) tok];
                        float[][][] newDkv = new float[def.numLayers()][2][];
                        for (int l = 0; l < def.numLayers(); l++) {
                            newDkv[l][0] = tensorData(r, "present." + l + ".decoder.key");
                            newDkv[l][1] = tensorData(r, "present." + l + ".decoder.value");
                        }
                        if (tok == def.eosId()) {
                            // 完成假设（不含 EOS token）
                            finished.add(new Beam(b.ids, score, newDkv, b.decSeq + 1, true));
                        } else if (tok == def.decoderStartId()) {
                            // 忽略重复起始 token
                        } else {
                            List<Long> ids = new ArrayList<>(b.ids);
                            ids.add(tok);
                            next.add(new Beam(ids, score, newDkv, b.decSeq + 1, false));
                        }
                    }
                }
            }
            // 保留分数最高的 numBeams 个活跃假设
            next.sort((a, b) -> Float.compare(b.score, a.score));
            if (next.size() > numBeams) {
                next = new ArrayList<>(next.subList(0, numBeams));
            }
            beams = next;
        }

        if (!finished.isEmpty()) {
            finished.sort((a, b) -> Float.compare(b.score, a.score));
            return finished.get(0).ids;
        }
        if (!beams.isEmpty()) {
            beams.sort((a, b) -> Float.compare(b.score, a.score));
            return beams.get(0).ids;
        }
        return List.of();
    }

    /**
     * 深拷贝 KV 缓存数组。
     *
     * @param src 源 KV
     * @return 副本
     */
    private static float[][][] cloneKv(float[][][] src) {
        float[][][] copy = new float[src.length][][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = new float[2][];
            for (int j = 0; j < 2; j++) {
                copy[i][j] = src[i][j] == null ? null : src[i][j].clone();
            }
        }
        return copy;
    }

    /**
     * 取 logits 行的 Top-K token id（按分数降序）。
     * <p>应用重复惩罚、禁止 decoder 起始 token、可选禁用 EOS。</p>
     *
     * @param row       logits 行
     * @param k         返回数量
     * @param seen      已生成 token（重复惩罚）
     * @param allowEos  是否允许 EOS
     * @return token id 列表
     */
    private List<Long> topKTokens(float[] row, int k, List<Long> seen, boolean allowEos) {
        int[] order = new int[row.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // 应用重复惩罚
        Set<Long> set = new HashSet<>(seen);
        for (Long token : set) {
            int idx = token.intValue();
            if (idx >= 0 && idx < row.length) {
                float v = row[idx];
                row[idx] = v < 0 ? v * REPETITION_PENALTY : v / REPETITION_PENALTY;
            }
        }
        row[(int) def.decoderStartId()] = Float.NEGATIVE_INFINITY;
        if (!allowEos) {
            row[(int) def.eosId()] = Float.NEGATIVE_INFINITY;
        }
        // 简单选择排序取 Top-K
        int count = Math.min(k, row.length);
        List<Long> result = new ArrayList<>(count);
        boolean[] picked = new boolean[row.length];
        for (int n = 0; n < count; n++) {
            int best = -1;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int i = 1; i < row.length; i++) {
                if (!picked[i] && row[i] > bestScore) {
                    bestScore = row[i];
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            picked[best] = true;
            result.add((long) best);
        }
        return result;
    }

    /**
     * 束搜索候选。
     *
     * @param ids      已生成 token
     * @param score    累计分数（logits 和）
     * @param dKv      解码器 KV 缓存
     * @param decSeq   当前解码序列长度
     * @param finished 是否已结束（含 EOS）
     */
    private record Beam(List<Long> ids, float score, float[][][] dKv, int decSeq, boolean finished) {
    }
}