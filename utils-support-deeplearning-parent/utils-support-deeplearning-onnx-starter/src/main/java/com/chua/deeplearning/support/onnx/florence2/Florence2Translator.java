package com.chua.deeplearning.support.onnx.florence2;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Florence-2 多模态理解 Translator（ONNX Runtime）。
 * <p>
 * 输入：Object[]{byte[] imageData, String taskPrompt}
 * taskPrompt 示例：{@code "<CAPTION>"}、{@code "<OCR>"}、{@code "<OD>"}、{@code "<DETAILED_CAPTION>"}
 * </p>
 * <p>
 * 模型来源：onnx-community/Florence-2-base-ft（HuggingFace，MIT 协议）
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Florence2Translator implements ITranslator<Object[], String> {

    private static final String NAME = "florence2";
    private static final int IMAGE_SIZE = 768;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final int ENCODER_SEQ_LEN = 577;
    private static final int NUM_LAYERS = 6;
    private static final int NUM_HEADS = 12;
    private static final int HEAD_DIM = 64;
    private static final int HIDDEN_SIZE = 768;
    private static final int VOCAB_SIZE = 51289;
    private static final long EOS_ID = 2L;
    private static final int MAX_NEW_TOKENS = 100;
    private static final String MODEL_DIR = "vision/florence2/";
    private static final String CACHE_ROOT = System.getProperty("deeplearning.model.cache-dir",
            System.getProperty("java.io.tmpdir"));

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession visionSession;
    private OrtSession embedSession;
    private OrtSession decoderSession;
    private volatile boolean prepared;

    @Override
    public String name() {
        return NAME;
    }

    private synchronized void prepare() throws Exception {
        if (prepared) return;
        Path modelDir = Path.of(CACHE_ROOT, MODEL_DIR);
        if (!Files.exists(modelDir)) Files.createDirectories(modelDir);
        downloadModels(modelDir);

        Path visionPath = modelDir.resolve("vision_encoder.onnx");
        Path embedPath = modelDir.resolve("embed_tokens.onnx");
        Path decoderPath = modelDir.resolve("decoder_model_merged.onnx");
        Path tokenizerPath = modelDir.resolve("tokenizer.json");

        if (!Files.exists(visionPath) || !Files.exists(decoderPath) || !Files.exists(tokenizerPath)) {
            throw new IllegalStateException("Florence-2 model files missing: vision=" + visionPath
                    + " decoder=" + decoderPath + " tokenizer=" + tokenizerPath);
        }

        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(false)
                .optMaxLength(128)
                .build();

        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        opts.setInterOpNumThreads(2);

        visionSession = ortEnv.createSession(visionPath.toString(), opts);
        embedSession = ortEnv.createSession(embedPath.toString(), opts);
        decoderSession = ortEnv.createSession(decoderPath.toString(), opts);

        log.info("[Florence-2] Model loaded: vision={} embed={} decoder={}",
                visionPath, embedPath, decoderPath);
        prepared = true;
    }

    private void downloadModels(Path modelDir) {
        String base = "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/main/onnx/";
        for (String f : new String[]{"vision_encoder.onnx", "embed_tokens.onnx", "decoder_model_merged.onnx"}) {
            Path target = modelDir.resolve(f);
            if (Files.exists(target)) continue;
            try {
                Files.copy(java.net.URI.create(base + f).toURL().openStream(),
                        target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("[Florence-2] Downloaded: {} ({}MB)", f, target.toFile().length() / 1024 / 1024);
            } catch (Exception e) {
                log.warn("[Florence-2] Download failed {}: {}", f, e.getMessage());
            }
        }
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        if (!Files.exists(tokenizerPath)) {
            try {
                java.net.URI uri = new java.net.URI(
                        "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/main/tokenizer.json");
                Files.copy(uri.toURL().openStream(), tokenizerPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                log.warn("[Florence-2] Tokenizer download failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public String translate(Object[] input) {
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
            float[] pixels = preprocessImage(imageData);
            float[][] encoderHidden = inferVision(pixels);
            return generate(encoderHidden, taskPrompt).trim();
        } catch (Exception e) {
            throw new RuntimeException("Florence-2 inference failed: " + e.getMessage(), e);
        }
    }

    private float[] preprocessImage(byte[] imageData) throws Exception {
        ImageUtils.load();
        Mat src = ImageUtils.decode(imageData);
        if (src == null || src.empty()) throw new IllegalArgumentException("Cannot decode image");
        try {
            Mat resized = new Mat();
            Imgproc.resize(src, resized, new org.opencv.core.Size(IMAGE_SIZE, IMAGE_SIZE), 0, 0, Imgproc.INTER_CUBIC);
            if (resized.channels() == 3) Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2RGB);
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
            return pixels;
        } finally { src.release(); }
    }

    private float[][] inferVision(float[] pixels) throws Exception {
        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(pixels), new long[]{1, 3, IMAGE_SIZE, IMAGE_SIZE})) {
            try (OrtSession.Result result = visionSession.run(Map.of("pixel_values", tensor))) {
                OnnxTensor features = (OnnxTensor) result.get("image_features").get();
                long[] s = features.getInfo().getShape();
                int seqLen = (int) s[1];
                float[] flat = features.getFloatBuffer().array();
                float[][] matrix = new float[seqLen][HIDDEN_SIZE];
                for (int i = 0; i < seqLen; i++) System.arraycopy(flat, i * HIDDEN_SIZE, matrix[i], 0, HIDDEN_SIZE);
                return matrix;
            }
        }
    }

    private String generate(float[][] encoderHidden, String taskPrompt) throws Exception {
        int seqLen = ENCODER_SEQ_LEN;
        long[] encMask = new long[seqLen];
        java.util.Arrays.fill(encMask, 1L);
        OnnxTensor encoderMaskTensor = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(encMask), new long[]{1, seqLen});

        List<Long> tokens = new ArrayList<>();
        try {
            Encoding enc = tokenizer.encode(taskPrompt);
            for (long t : enc.getIds()) tokens.add(t);
        } catch (Exception e) {
            log.warn("[Florence-2] tokenizer failed: {}", e.getMessage());
            for (char c : taskPrompt.toCharArray()) tokens.add((long) c);
        }

        List<Long> generatedTokens = new ArrayList<>(tokens);
        OnnxTensor[][] pastKV = null;
        boolean firstStep = true;

        try {
            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                long[] currentIds = generatedTokens.stream().mapToLong(Long::longValue).toArray();
                int currentLen = currentIds.length;

                OnnxTensor idsTensor = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(currentIds), new long[]{1, currentLen});
                try (OrtSession.Result embedResult = embedSession.run(Map.of("input_ids", idsTensor))) {
                    OnnxTensor embedOut = (OnnxTensor) embedResult.get("inputs_embeds").get();
                    int embedSeqLen = (int) embedOut.getInfo().getShape()[1];
                    float[][] embeds = new float[embedSeqLen][HIDDEN_SIZE];
                    float[] embedFlat = embedOut.getFloatBuffer().array();
                    for (int i = 0; i < embedSeqLen; i++) System.arraycopy(embedFlat, i * HIDDEN_SIZE, embeds[i], 0, HIDDEN_SIZE);

                    OnnxTensor embedsTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayFrom2D(embeds)), new long[]{1, embedSeqLen, HIDDEN_SIZE});
                    OnnxTensor encoderHiddenTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayFrom2D(encoderHidden)), new long[]{1, seqLen, HIDDEN_SIZE});
                    OnnxTensor useCacheTensor = OnnxTensor.createTensor(ortEnv, new long[]{firstStep ? 0L : 1L});

                    Map<String, OnnxTensor> decoderInputs = new HashMap<>();
                    decoderInputs.put("inputs_embeds", embedsTensor);
                    decoderInputs.put("encoder_hidden_states", encoderHiddenTensor);
                    decoderInputs.put("encoder_attention_mask", encoderMaskTensor);

                    if (pastKV != null) {
                        for (int l = 0; l < NUM_LAYERS; l++) {
                            decoderInputs.put("past_key_values." + l + ".decoder.key", pastKV[l][0]);
                            decoderInputs.put("past_key_values." + l + ".decoder.value", pastKV[l][1]);
                            decoderInputs.put("past_key_values." + l + ".encoder.key", pastKV[l][2]);
                            decoderInputs.put("past_key_values." + l + ".encoder.value", pastKV[l][3]);
                        }
                    } else {
                        for (int l = 0; l < NUM_LAYERS; l++) {
                            long[] sDK = {1, NUM_HEADS, 0, HEAD_DIM};
                            decoderInputs.put("past_key_values." + l + ".decoder.key",
                                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(new float[0]), sDK));
                            decoderInputs.put("past_key_values." + l + ".decoder.value",
                                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(new float[0]), sDK));
                            long[] sEK = {1, NUM_HEADS, seqLen, HEAD_DIM};
                            float[] zeroE = new float[NUM_HEADS * seqLen * HEAD_DIM];
                            decoderInputs.put("past_key_values." + l + ".encoder.key",
                                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(zeroE), sEK));
                            decoderInputs.put("past_key_values." + l + ".encoder.value",
                                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(zeroE), sEK));
                        }
                    }
                    decoderInputs.put("use_cache_branch", useCacheTensor);

                    try (OrtSession.Result decodeResult = decoderSession.run(decoderInputs)) {
                        OnnxTensor logitsTensor = (OnnxTensor) decodeResult.get("logits").get();
                        float[] logits = logitsTensor.getFloatBuffer().array();
                        int nextToken = argmax(logits, (embedSeqLen - 1) * VOCAB_SIZE, VOCAB_SIZE);
                        if (nextToken == EOS_ID) { log.debug("[Florence-2] EOS at step {}", step); break; }
                        generatedTokens.add((long) nextToken);

                        OnnxTensor[][] newKV = new OnnxTensor[NUM_LAYERS][];
                        for (int l = 0; l < NUM_LAYERS; l++) {
                            newKV[l] = new OnnxTensor[4];
                            newKV[l][0] = (OnnxTensor) decodeResult.get("present." + l + ".decoder.key").get();
                            newKV[l][1] = (OnnxTensor) decodeResult.get("present." + l + ".decoder.value").get();
                            newKV[l][2] = (OnnxTensor) decodeResult.get("present." + l + ".encoder.key").get();
                            newKV[l][3] = (OnnxTensor) decodeResult.get("present." + l + ".encoder.value").get();
                        }
                        if (pastKV != null) for (OnnxTensor[] layer : pastKV) for (OnnxTensor t : layer) if (t != null) t.close();
                        pastKV = newKV;
                        firstStep = false;
                    }
                    embedsTensor.close(); encoderHiddenTensor.close(); useCacheTensor.close(); idsTensor.close();
                }
            }
        } finally {
            encoderMaskTensor.close();
            if (pastKV != null) for (OnnxTensor[] layer : pastKV) for (OnnxTensor t : layer) if (t != null) t.close();
        }

        long[] finalIds = generatedTokens.stream().mapToLong(Long::longValue).toArray();
        try { return tokenizer.decode(finalIds); }
        catch (Exception e) { log.warn("[Florence-2] decode failed: {}", e.getMessage()); return joinTokens(generatedTokens); }
    }

    private static String joinTokens(List<Long> tokens) {
        StringBuilder sb = new StringBuilder();
        for (long t : tokens) sb.append((char) Math.min(t, 0x10FFFFL));
        return sb.toString();
    }

    private static float[] floatArrayFrom2D(float[][] m) {
        int r = m.length, c = m[0].length;
        float[] flat = new float[r * c];
        for (int i = 0; i < r; i++) System.arraycopy(m[i], 0, flat, i * c, c);
        return flat;
    }

    private static int argmax(float[] logits, int offset, int vocabSize) {
        int maxIdx = 0; float maxVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vocabSize; i++) { float v = logits[offset + i]; if (v > maxVal) { maxVal = v; maxIdx = i; } }
        return maxIdx;
    }

    public void close() {
        prepared = false;
        if (tokenizer != null) { try { tokenizer.close(); } catch (Exception ignored) {} tokenizer = null; }
        closeS(visionSession); closeS(embedSession); closeS(decoderSession);
    }
    private static void closeS(OrtSession s) { if (s != null) { try { s.close(); } catch (Exception ignored) {} } }
}
