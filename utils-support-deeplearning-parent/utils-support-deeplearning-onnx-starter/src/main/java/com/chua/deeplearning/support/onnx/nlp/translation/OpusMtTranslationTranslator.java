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
* 通用 marianmt 嵌入式机器翻译器（ORT 原生 + huggingface Tokenizer）。
*
* <p>支持多语言对（英中/中英/英法/英德/英西/英俄/中日等）。Marian 为 encoder-decoder 自回归架构：
* <ol>
*   <li>encoder_model：{@code input_ids + attention_mask} → {@code last_hidden_state}</li>
*   <li>decoder_model（首步）：{@code encoder_hidden_states + decoder_start(65000)} → logits + present KV</li>
*   <li>decoder_with_past_model（循环）：{@code input_ids + past_key_values} → logits + present KV</li>
*   <li>贪心解码 + repetition penalty，遇 EOS(0)/候选分隔符(15) 停止，取第一个候选</li>
* </ol></p>
*
* <p>模型加载优先级：模型 ID 注册相对路径（classpath/jar 内嵌，嵌入式优先）→ downloadUrl 自动下载缓存。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class OpusMtTranslationTranslator implements ITranslator<String, String>, AutoCloseable {

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

    /**
    * 模型 标识（registry 标识，用于路径解析/下载缓存隔离）。
    */
    private final String modelId;

    /**
    * jar 内资源根目录（嵌入式，如 {@code nlp/translation/opus_mt_en_zh/}）。
    */
    private final String resourceBase;

    /**
    * HF 模型仓库 onnx 目录 URL（downloadurl 模式，如 {@code https://huggingface.co/Xenova/opus-mt-en-zh/resolve/main/onnx}）。
    */
    private final String downloadBaseUrl;

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;
    /** 编码器会话 */
    private OrtSession encoderSession;
    /** 解码器会话 */
    private OrtSession decoderSession;
    /** 解码器历史会话 */
    private OrtSession decoderPastSession;
    /** 分词器 */
    private HuggingFaceTokenizer tokenizer;
    /** 是否已加载 */
    private volatile boolean loaded;

    /**
    * 构造通用 marianmt 翻译器。
    *
    * @param modelId         模型 标识（registry 标识）
    * @param resourceBase    嵌入式 jar 资源根目录；为空时走 downloadurl 下载
    * @param downloadBaseUrl HF 模型仓库 onnx 目录 URL；为空时表示仅嵌入式
    */
    public OpusMtTranslationTranslator(String modelId, String resourceBase, String downloadBaseUrl) {
        this.modelId = modelId;
        this.resourceBase = resourceBase;
        this.downloadBaseUrl = downloadBaseUrl;
    }

    /**
    * 懒加载模型与 tokenizer。
    *
    * <p>优先从 classpath/jar 内嵌资源解压；否则从 downloadBaseUrl 下载四文件到缓存目录。</p>
    */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path modelDir = resolveModelDir();
        Path encoderPath = modelDir.resolve(ENCODER_FILE);
        Path decoderPath = modelDir.resolve(DECODER_FILE);
        Path decoderPastPath = modelDir.resolve(DECODER_PAST_FILE);
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        if (!Files.isRegularFile(encoderPath) || !Files.isRegularFile(decoderPath)
                || !Files.isRegularFile(decoderPastPath) || !Files.isRegularFile(tokenizerPath)) {
            throw new IllegalArgumentException(modelId + " 模型资源缺失: " + modelDir);
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
        log.info("[{}] 模型加载完成: encoder={} decoder={} decoder_past={}",
                modelId, encoderPath.getFileName(), decoderPath.getFileName(), decoderPastPath.getFileName());
        loaded = true;
    }

    /**
    * 解析模型目录：嵌入式 jar → 解压临时目录；否则从 HF 下载四文件到缓存目录。
    *
    * @return 模型目录
    * @throws Exception 解析异常
    */
    private Path resolveModelDir() throws Exception {
        if (resourceBase != null && !resourceBase.isBlank()) {
            Path tmpDir = Files.createTempDirectory(modelId + "-");
            tmpDir.toFile().deleteOnExit();
            Path modelDir = tmpDir.resolve(modelId);
            Files.createDirectories(modelDir);
            NativeLoader.of(modelId)
                    .from(OpusMtTranslationTranslator.class.getClassLoader())
                    .basePath(resourceBase)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            return modelDir;
        }
        if (downloadBaseUrl == null || downloadBaseUrl.isBlank()) {
            throw new IllegalArgumentException(modelId + " 未配置资源位置");
        }
 // downloadurl 模式：下载四文件到缓存目录 %TEMP%/chua-dl-模型/{模型标识}
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "chua-dl-models", modelId);
        Files.createDirectories(cacheDir);
        String[] files = {ENCODER_FILE, DECODER_FILE, DECODER_PAST_FILE, "tokenizer.json"};
        for (String file : files) {
            Path target = cacheDir.resolve(file);
            if (Files.exists(target) && Files.size(target) > 0) {
                continue;
            }
            String url = downloadBaseUrl + "/" + file;
            log.info("[{}] 下载模型文件: {}", modelId, url);
            Path tmp = cacheDir.resolve(file + ".part");
            try (java.io.InputStream in = new java.net.URL(url).openStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[{}] 模型文件下载完成: {}", modelId, target);
        }
        return cacheDir;
    }

    @Override
    /** 名称 */
    public String name() {
        return modelId;
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
            if (Boolean.getBoolean(modelId + ".debug")) {
                log.info("[{}] raw decoded: {} | ids: {}", modelId, decoded, java.util.Arrays.toString(tokenIds));
            }
            return postProcess(decoded);
        } catch (Exception e) {
            throw new RuntimeException("[" + modelId + "] 翻译失败: " + e.getMessage(), e);
        }
    }

    /**
    * 后处理：截取第一个完整翻译候选。
    *
    * <p>Marian 贪心解码会生成多个候选（以 " - " 分隔）或附加冗余尾巴。策略：优先取 " - " 前；
    * 否则取第一个句号/感叹号后的完整句（保留标点），丢弃剩余尾巴。</p>
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
