package com.chua.deeplearning.support.onnx.florence2;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Florence-2 视觉理解翻译器，支持多种图像理解任务。
 *
 * <p>使用 ONNX Runtime 加载 Florence-2 模型，
 * 支持 caption、detailed caption、OCR、目标检测等任务类型。
 *
 * @author CH
 * @since 4.0.0.42
 * @param tokens 令牌
 * @return 连接令牌的结果
 * @param encoderHidden 编码器hidden
 * @param taskPrompt 任务提示符
 * @param pixels pixels
 */
public class Florence2Translator implements ITranslator<Object[], String> {
    private static final Logger log = LoggerFactory.getLogger(Florence2Translator.class); // 日志
    private static final String NAME = "florence2"; // 名称
    private static final int IMAGE_SIZE = 768; // 镜像大小
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f}; // MEAN
    private static final float[] STD = {0.229f, 0.224f, 0.225f}; // STD
    private static final int ENCODER_SEQ_LEN = 577; // 编码器seqlen
    private static final int NUM_LAYERS = 6; // NUM_LAYERS
    private static final int NUM_HEADS = 12; // NUM_HEADS
    private static final int HEAD_DIM = 64; // HEAD_DIM
    private static final int HIDDEN_SIZE = 768; // hidden大小
    private static final int VOCAB_SIZE = 51289; // vocab大小
    private static final long EOS_ID = 2L; // EOS_标识
    private static final int MAX_NEW_TOKENS = 100; // 最大新令牌
    private static final String MODEL_DIR = "vision/florence2/"; // 模型dir
    private static final String CACHE_ROOT = System.getProperty("deeplearning.model.cache-dir", System.getProperty("java.io.tmpdir")); // 缓存根
    private HuggingFaceTokenizer tokenizer; // tokenizer
    private OrtEnvironment ortEnv; // ortenv
    /**
     * prepare。
     */
    private OrtSession visionSession;
    private OrtSession embedSession; // embed会话
    private OrtSession decoderSession; // 解码器会话
    private volatile boolean prepared; // prepared
    @Override public String name() { return NAME; }
    private synchronized void prepare() throws Exception {
        if (prepared) {
            return;
        }
        Path modelDir = Path.of(CACHE_ROOT, MODEL_DIR);
        if (!Files.exists(modelDir)) {
            Files.createDirectories(modelDir);
        }
        downloadModels(modelDir);
        Path visionPath = modelDir.resolve("vision_encoder.onnx");
        Path embedPath = modelDir.resolve("embed_tokens.onnx");
        Path decoderPath = modelDir.resolve("decoder_model_merged.onnx");
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        if (!Files.exists(visionPath) || !Files.exists(decoderPath) || !Files.exists(tokenizerPath)) {
            throw new IllegalStateException("Florence-2 model files missing: vision=" + visionPath + " decoder=" + decoderPath);
        }
        tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerPath).optPadding(false).optMaxLength(128).build();
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        opts.setInterOpNumThreads(2);
        visionSession = ortEnv.createSession(visionPath.toString(), opts);
        /**
         * download模型。
         * @param modelDir 模型dir
         */
        embedSession = ortEnv.createSession(embedPath.toString(), opts);
        decoderSession = ortEnv.createSession(decoderPath.toString(), opts);
        log.info("[Florence-2] Model loaded: vision={} embed={} decoder={}", visionPath, embedPath, decoderPath);
        prepared = true;
    }
    /**
     * downloadModels。
     *
     * @param modelDir 模型目录，不允许为 null
     */
    private void downloadModels(Path modelDir) {
        String base = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/main/onnx/";
        for (String f : new String[]{"vision_encoder.onnx", "embed_tokens.onnx", "decoder_model_merged.onnx"}) {
            Path target = modelDir.resolve(f);
            if (Files.exists(target)) {
                continue;
            }
            try {
                Files.copy(java.net.URI.create(base + f).toURL().openStream(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("[Florence-2] Downloaded: {} ({}MB)", f, target.toFile().length() / 1024 / 1024);
            } catch (Exception e) { log.warn("[Florence-2] Download failed {}: {}", f, e.getMessage()); }
        }
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        if (!Files.exists(tokenizerPath)) {
            try {
                java.net.URI uri = new java.net.URI("https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/main/tokenizer.json");
                Files.copy(uri.toURL().openStream(), tokenizerPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { log.warn("[Florence-2] Tokenizer download failed: {}", e.getMessage()); }
        }
    }
    @Override public String translate(Object[] input) {
        try {
            if (input == null || input.length < 2) {
                throw new IllegalArgumentException("Input: Object[]{byte[] image, String taskPrompt}");
            }
            byte[] imageData = (byte[]) input[0];
            String taskPrompt = (String) input[1];
            if (imageData == null || imageData.length == 0) {
                throw new IllegalArgumentException("Image data is empty");
            }
            prepare();
            /**
             * preprocess镜像。
             * @param imageData 镜像数据
             * @return preprocess镜像的结果
             * @param tokens 令牌
             * @param encoderHidden 编码器hidden
             * @param taskPrompt 任务提示符
             */
            float[] pixels = preprocessImage(imageData);
            float[][] encoderHidden = inferVision(pixels);
            /**
             * preprocess镜像。
             * @param imageData 镜像数据
             * @return preprocess镜像的结果
             */
            return generate(encoderHidden, taskPrompt).trim();
        } catch (Exception e) { throw new RuntimeException("Florence-2 inference failed: " + e.getMessage(), e); }
    }
    /**
     * preprocessImage。
     *
     * @param imageData image数据，不允许为 null
     * @return 结果值
     * @throws Exception 当执行过程不满足前置条件时
     */
    private float[] preprocessImage(byte[] imageData) throws Exception {
        ImageUtils.load();
        Mat src = ImageUtils.decode(imageData);
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("Cannot decode image");
        }
        try {
            Mat resized = new Mat();
            Imgproc.resize(src, resized, new org.opencv.core.Size(IMAGE_SIZE, IMAGE_SIZE), 0, 0, Imgproc.INTER_CUBIC);
            if (resized.channels() == 3) {
                Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2RGB);
            }
            float[] pixels = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
            for (int y = 0; y < IMAGE_SIZE; y++) {
                for (int x = 0; x < IMAGE_SIZE; x++) {
                    double[] rgb = resized.get(y, x);
                    float r = (float) rgb[0] / 255.0f, g = (float) rgb[1] / 255.0f, b = (float) rgb[2] / 255.0f;
                    int idx = y * IMAGE_SIZE + x;
                    pixels[idx] = (r - MEAN[0]) / STD[0];
                    pixels[IMAGE_SIZE * IMAGE_SIZE + idx] = (g - MEAN[1]) / STD[1];
                    pixels[2 * IMAGE_SIZE * IMAGE_SIZE + idx] = (b - MEAN[2]) / STD[2];
                }
            }
            resized.release();
            /**
             * inferVision。
             * @param pixels pixels
             * @return inferVision的结果
             */
            return pixels;
        } finally { src.release(); }
    }
    /**
     * inferVision。
     *
     * @param pixels 方法入参 pixels
     * @return 结果值
     * @throws Exception 当执行过程不满足前置条件时
     */
    private float[][] inferVision(float[] pixels) throws Exception {
        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(pixels), new long[]{1, 3, IMAGE_SIZE, IMAGE_SIZE})) {
            try (OrtSession.Result result = visionSession.run(Map.of("pixel_values", tensor))) {
                OnnxTensor features = (OnnxTensor) result.get("image_features").get();
                long[] s = features.getInfo().getShape();
                int seqLen = (int) s[1];
                float[] flat = features.getFloatBuffer().array();
                float[][] matrix = new float[seqLen][HIDDEN_SIZE];
                for (int i = 0; i < seqLen; i++) {
                    System.arraycopy(flat, i * HIDDEN_SIZE, matrix[i], 0, HIDDEN_SIZE);
                }
                return matrix;
            }
        }
    }
    /**
     * generate。
     *
     * @param encoderHidden 方法入参 encoderHidden
     * @param taskPrompt task提示词，不允许为 null
     * @return 结果字符串
     * @throws Exception 当执行过程不满足前置条件时
     */
    private String generate(float[][] encoderHidden, String taskPrompt) throws Exception {
        int seqLen = ENCODER_SEQ_LEN;
        long[] encMask = new long[seqLen];
        java.util.Arrays.fill(encMask, 1L);
        OnnxTensor encoderMaskTensor = OnnxTensor.createTensor(ortEnv,
                java.nio.LongBuffer.wrap(encMask), new long[]{1, seqLen});
        List<Long> tokens = new ArrayList<>();
        try { Encoding enc = tokenizer.encode(taskPrompt); for (long t : enc.getIds()) tokens.add(t); }
        catch (Exception e) { log.warn("[Florence-2] tokenizer failed: {}", e.getMessage()); for (char c : taskPrompt.toCharArray()) tokens.add((long) c); }
        List<Long> generatedTokens = new ArrayList<>(tokens);
        try {
            long[] currentIds = tokens.stream().mapToLong(Long::longValue).toArray();
            OnnxTensor idsTensor = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(currentIds), new long[]{1, currentIds.length});
            try (OrtSession.Result embedResult = embedSession.run(Map.of("input_ids", idsTensor))) {
                OnnxTensor embedOut = (OnnxTensor) embedResult.get("inputs_embeds").get();
                int embedSeqLen = (int) embedOut.getInfo().getShape()[1];
                float[][] embeds = new float[embedSeqLen][HIDDEN_SIZE];
                float[] embedFlat = embedOut.getFloatBuffer().array();
                for (int i = 0; i < embedSeqLen; i++) {
                    System.arraycopy(embedFlat, i * HIDDEN_SIZE, embeds[i], 0, HIDDEN_SIZE);
                }
                OnnxTensor embedsTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayFrom2D(embeds)), new long[]{1, embedSeqLen, HIDDEN_SIZE});
                OnnxTensor encoderHiddenTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayFrom2D(encoderHidden)), new long[]{1, seqLen, HIDDEN_SIZE});
                Map<String, OnnxTensor> decoderInputs = new HashMap<>();
                decoderInputs.put("inputs_embeds", embedsTensor);
                decoderInputs.put("encoder_hidden_states", encoderHiddenTensor);
                decoderInputs.put("encoder_attention_mask", encoderMaskTensor);
                decoderInputs.put("use_cache_branch", OnnxTensor.createTensor(ortEnv,
                        java.nio.ByteBuffer.wrap(new byte[]{0}), new long[]{1}, ai.onnxruntime.OnnxJavaType.BOOL));
                try (OrtSession.Result decodeResult = decoderSession.run(decoderInputs)) {
                    OnnxTensor logitsTensor = (OnnxTensor) decodeResult.get("logits").get();
                    float[] logits = logitsTensor.getFloatBuffer().array();
                    int nextToken = argmax(logits, (embedSeqLen - 1) * VOCAB_SIZE, VOCAB_SIZE);
                    if (nextToken == EOS_ID) { log.debug("[Florence-2] EOS at step 0"); }
                    else { generatedTokens.add((long) nextToken); }
                    OnnxTensor[][] pastKV = new OnnxTensor[NUM_LAYERS][];
                    for (int l = 0; l < NUM_LAYERS; l++) {
                        pastKV[l] = new OnnxTensor[4];
                        pastKV[l][0] = (OnnxTensor) decodeResult.get("present." + l + ".decoder.key").get();
                        pastKV[l][1] = (OnnxTensor) decodeResult.get("present." + l + ".decoder.value").get();
                        pastKV[l][2] = (OnnxTensor) decodeResult.get("present." + l + ".encoder.key").get();
                        pastKV[l][3] = (OnnxTensor) decodeResult.get("present." + l + ".encoder.value").get();
                    }
                    for (int step = 1; step < MAX_NEW_TOKENS; step++) {
                        long[] stepIds = generatedTokens.stream().mapToLong(Long::longValue).toArray();
                        OnnxTensor stepIdsTensor = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(stepIds), new long[]{1, stepIds.length});
                        try (OrtSession.Result stepEmbedResult = embedSession.run(Map.of("input_ids", stepIdsTensor))) {
                            OnnxTensor stepEmbedOut = (OnnxTensor) stepEmbedResult.get("inputs_embeds").get();
                            int stepSeqLen = (int) stepEmbedOut.getInfo().getShape()[1];
                            float[][] stepEmbeds = new float[stepSeqLen][HIDDEN_SIZE];
                            float[] stepEmbedFlat = stepEmbedOut.getFloatBuffer().array();
                            for (int i = 0; i < stepSeqLen; i++) {
                                System.arraycopy(stepEmbedFlat, i * HIDDEN_SIZE, stepEmbeds[i], 0, HIDDEN_SIZE);
                            }
                            OnnxTensor stepEmbedsTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayFrom2D(stepEmbeds)), new long[]{1, stepSeqLen, HIDDEN_SIZE});
                            OnnxTensor stepEncoderHiddenTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayFrom2D(encoderHidden)), new long[]{1, seqLen, HIDDEN_SIZE});
                            Map<String, OnnxTensor> stepDecoderInputs = new HashMap<>();
                            stepDecoderInputs.put("inputs_embeds", stepEmbedsTensor);
                            stepDecoderInputs.put("encoder_hidden_states", stepEncoderHiddenTensor);
                            stepDecoderInputs.put("encoder_attention_mask", encoderMaskTensor);
                            stepDecoderInputs.put("use_cache_branch", OnnxTensor.createTensor(ortEnv,
                                        java.nio.ByteBuffer.wrap(new byte[]{1}), new long[]{1}, ai.onnxruntime.OnnxJavaType.BOOL));
                            for (int l = 0; l < NUM_LAYERS; l++) {
                                stepDecoderInputs.put("past_key_values." + l + ".decoder.key", pastKV[l][0]);
                                stepDecoderInputs.put("past_key_values." + l + ".decoder.value", pastKV[l][1]);
                                stepDecoderInputs.put("past_key_values." + l + ".encoder.key", pastKV[l][2]);
                                stepDecoderInputs.put("past_key_values." + l + ".encoder.value", pastKV[l][3]);
                            }
                            try (OrtSession.Result stepDecodeResult = decoderSession.run(stepDecoderInputs)) {
                                OnnxTensor stepLogitsTensor = (OnnxTensor) stepDecodeResult.get("logits").get();
                                float[] stepLogits = stepLogitsTensor.getFloatBuffer().array();
                                int nextTok = argmax(stepLogits, (stepSeqLen - 1) * VOCAB_SIZE, VOCAB_SIZE);
                                if (nextTok == EOS_ID) {
                                    log.debug("[Florence-2] EOS at step {}", step);
                                    break;
                                }
                                generatedTokens.add((long) nextTok);
                                for (int l = 0; l < NUM_LAYERS; l++) {
                                    pastKV[l][0] = (OnnxTensor) stepDecodeResult.get("present." + l + ".decoder.key").get();
                                    pastKV[l][1] = (OnnxTensor) stepDecodeResult.get("present." + l + ".decoder.value").get();
                                    pastKV[l][2] = (OnnxTensor) stepDecodeResult.get("present." + l + ".encoder.key").get();
                                    pastKV[l][3] = (OnnxTensor) stepDecodeResult.get("present." + l + ".encoder.value").get();
                                }
                            }
                            stepEmbedsTensor.close();
                            stepEncoderHiddenTensor.close();
                            stepIdsTensor.close();
                        }
                    }
                    embedsTensor.close();
                    encoderHiddenTensor.close();
                    idsTensor.close();
                }
            }
        } finally {
            encoderMaskTensor.close();
        }
        long[] finalIds = generatedTokens.stream().mapToLong(Long::longValue).toArray();
        try {
            return tokenizer.decode(finalIds);
        } catch (Exception e) {
            log.warn("[Florence-2] decode failed: {}", e.getMessage());
            return joinTokens(generatedTokens);
        }
    }
    /**
     * 按字符码位拼接 token，作为 tokenizer 解码失败时的兜底。
     *
     * @param tokens 生成的 token id 列表，不允许为 null
     * @return 拼接后的文本
     */
    private static String joinTokens(List<Long> tokens) {
        StringBuilder sb = new StringBuilder();
        for (long t : tokens) {
            sb.append((char) Math.min(t, 0x10FFFFL));
        }
        return sb.toString();
    }

    /**
     * 将二维浮点数组按行优先展平为一维数组。
     *
     * @param m 二维浮点数组，不允许为 null
     * @return 展平后的一维数组
     */
    private static float[] floatArrayFrom2D(float[][] m) {
        int r = m.length;
        int c = m[0].length;
        float[] flat = new float[r * c];
        for (int i = 0; i < r; i++) {
            System.arraycopy(m[i], 0, flat, i * c, c);
        }
        return flat;
    }

    /**
     * 在 logits 指定区间内求最大值的下标。
     *
     * @param logits 展平的 logits 数组，不允许为 null
     * @param offset 区间起始下标
     * @param vocabSize 词表大小，即区间长度
     * @return 区间内最大值对应的下标
     */
    private static int argmax(float[] logits, int offset, int vocabSize) {
        int maxIdx = 0;
        float maxVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vocabSize; i++) {
            float v = logits[offset + i];
            if (v > maxVal) {
                maxVal = v;
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    /**
     * 关闭翻译器，释放 tokenizer 与全部 ONNX 会话资源。
     */
    public void close() {
        prepared = false;
        if (tokenizer != null) {
            try {
                tokenizer.close();
            } catch (Exception ignored) {}
            tokenizer = null;
        }
        closeS(visionSession);
        closeS(embedSession);
        closeS(decoderSession);
    }
    /**
     * 关闭单个 ONNX 会话，异常静默忽略。
     *
     * @param s 待关闭的会话，可为 null
     */
    private static void closeS(OrtSession s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
    }
}
