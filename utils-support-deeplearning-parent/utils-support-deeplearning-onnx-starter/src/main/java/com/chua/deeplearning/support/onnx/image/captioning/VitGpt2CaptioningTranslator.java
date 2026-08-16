package com.chua.deeplearning.support.onnx.image.captioning;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ViT-GPT2 图像描述（Image Captioning）Translator。
 *
 * <p>基于 modelscope {@code Xenova/vit-gpt2-image-captioning}：ViT 图像编码器 +
 * GPT-2 文本解码器。输入图像字节，输出图像的文字描述。</p>
 *
 * <p>流程：图像 → resize/normalize → encoder（ViT）→ last_hidden_state →
 * decoder（GPT-2）自回归生成 → token 序列 → 文本。</p>
 *
 * <p>encoder 模型打包在 jar（vision/captioning/vit-gpt2/encoder_model_quantized.onnx）；
 * decoder 模型较大（~151MB）通过 ModelRegistry 自动下载或本地路径提供。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VitGpt2CaptioningTranslator implements ITranslator<byte[], String> {

    /**
     * 模型名称
     */
    private static final String NAME = "vit-gpt2-captioning";

    /**
     * 输入图像尺寸（ViT-Base 224）
     */
    private static final int IMAGE_SIZE = 224;

    /**
     * 图像均值（ImageNet）
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
     * 图像标准差（ImageNet）
     */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /**
     * 最大生成长度
     */
    private static final int MAX_NEW_TOKENS = 40;

    /**
     * GPT-2 EOS token id
     */
    private static final long EOS_ID = 50256L;

    /**
     * GPT-2 BOS 起始 token id
     */
    private static final long BOS_ID = 0L;

    /**
     * classpath 资源根路径
     */
    private static final String RESOURCE_BASE = "vision/captioning/vit-gpt2/";

    /**
     * encoder 模型文件名
     */
    private static final String ENCODER_FILE = "encoder_model_quantized.onnx";

    /**
     * decoder 模型文件名
     */
    private static final String DECODER_FILE = "decoder_model_quantized.onnx";

    /**
     * 缓存根目录
     */
    private static final String CACHE_ROOT = "vision/captioning/vit-gpt2/";

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private volatile boolean prepared;

    /**
     * 构造图像描述翻译器。
     */
    public VitGpt2CaptioningTranslator() {
    }

    /**
     * 准备模型（懒加载）。
     *
     * @param decoderModelPath decoder 模型路径（本地或远程缓存），null 时尝试从 ModelRegistry 解析
     * @throws Exception 准备异常
     */
    private synchronized void prepare(Path decoderModelPath) throws Exception {
        if (prepared) {
            return;
        }
        Path modelDir = Path.of(cacheRoot(), CACHE_ROOT);
        if (!Files.exists(modelDir.resolve(ENCODER_FILE)) || !Files.exists(modelDir.resolve("tokenizer.json"))) {
            NativeLoader.of("vit-gpt2-resources")
                    .from(VitGpt2CaptioningTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        Path encoderPath = modelDir.resolve(ENCODER_FILE);
        Path decoderPath = resolveDecoder(decoderModelPath, modelDir);
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        if (!Files.exists(encoderPath) || !Files.exists(decoderPath) || !Files.exists(tokenizerPath)) {
            throw new IllegalStateException("ViT-GPT2 模型资源缺失: encoder=" + encoderPath
                    + " decoder=" + decoderPath + " tokenizer=" + tokenizerPath);
        }
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(false)
                .optMaxLength(128)
                .build();
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        encoderSession = ortEnv.createSession(encoderPath.toString(), opts);
        decoderSession = ortEnv.createSession(decoderPath.toString(), opts);
        log.info("[ViT-GPT2] 模型加载完成: encoder={} decoder={}", encoderPath, decoderPath);
        prepared = true;
    }

    /**
     * 模型缓存根目录：优先读系统属性 {@code deeplearning.model.cache-dir}，
     * 未配置时回落 {@code %TEMP%}。
     *
     * @return 缓存根目录
     */
    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    /**
     * 解析 decoder 模型路径。
     *
     * @param configured 配置路径
     * @param modelDir   缓存目录
     * @return decoder 路径
     */
    private static Path resolveDecoder(Path configured, Path modelDir) {
        if (configured != null && Files.exists(configured)) {
            return configured;
        }
        Path cached = modelDir.resolve(DECODER_FILE);
        if (Files.exists(cached)) {
            return cached;
        }
        // 尝试从 ModelRegistry 下载缓存
        try {
            Path resolved = com.chua.deeplearning.support.engine.ModelRegistry.resolveModelPath("vit-gpt2-captioning");
            if (resolved != null && Files.exists(resolved)) {
                return resolved;
            }
        } catch (Exception e) {
            log.warn("[ViT-GPT2] ModelRegistry 解析 decoder 失败: {}", e.getMessage());
        }
        return cached;
    }

    /**
     * 生成图像描述。
     *
     * @param imageData      图像字节
     * @param decoderModelPath decoder 模型路径（可为 null）
     * @return 图像描述文本
     */
    public String caption(byte[] imageData, Path decoderModelPath) {
        try {
            if (imageData == null || imageData.length == 0) {
                throw new IllegalArgumentException("图像字节为空");
            }
            prepare(decoderModelPath);
            float[][] encoderHidden = inferEncoder(imageData);
            String text = generate(encoderHidden);
            return text.trim();
        } catch (Exception e) {
            throw new RuntimeException("ViT-GPT2 图像描述失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String translate(byte[] input) {
        return caption(input, null);
    }

    /**
     * encoder 推理：图像 → last_hidden_state。
     *
     * @param imageData 图像字节
     * @return hidden state，行 = token，列 = 768
     * @throws Exception 推理异常
     */
    private float[][] inferEncoder(byte[] imageData) throws Exception {
        float[] pixels = preprocessImage(imageData);
        long[] shape = {1, 3, IMAGE_SIZE, IMAGE_SIZE};
        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(pixels), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("pixel_values", tensor);
            try (OrtSession.Result result = encoderSession.run(inputs)) {
                OnnxTensor hidden = (OnnxTensor) result.get("last_hidden_state").get();
                long[] hiddenShape = hidden.getInfo().getShape();
                int seq = (int) hiddenShape[1];
                int hiddenSize = (int) hiddenShape[2];
                float[] flat = hidden.getFloatBuffer().array();
                float[][] matrix = new float[seq][hiddenSize];
                for (int i = 0; i < seq; i++) {
                    System.arraycopy(flat, i * hiddenSize, matrix[i], 0, hiddenSize);
                }
                return matrix;
            }
        }
    }

    /**
     * 图像预处理：解码 → resize → CHW → normalize。
     *
     * <p>使用 Java2D 缩放图像（避免依赖 DJL NDManager 引擎），
     * 输出 [3, 224, 224] 归一化像素（RGB 顺序）。</p>
     *
     * @param imageData 图像字节
     * @return 归一化像素 [3, 224, 224]
     * @throws Exception 预处理异常
     */
    private float[] preprocessImage(byte[] imageData) throws Exception {
        // 加载 OpenCV 原生库（openpnp）
        nu.pattern.OpenCV.loadLocally();

        Mat src = Imgcodecs.imdecode(new MatOfByte(imageData), Imgcodecs.IMREAD_COLOR);
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("无法解码图像");
        }
        try {
            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(IMAGE_SIZE, IMAGE_SIZE), 0, 0, Imgproc.INTER_CUBIC);

            float[] pixels = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
            for (int y = 0; y < IMAGE_SIZE; y++) {
                for (int x = 0; x < IMAGE_SIZE; x++) {
                    double[] bgr = resized.get(y, x);
                    float b = (float) bgr[0] / 255.0f;
                    float g = (float) bgr[1] / 255.0f;
                    float r = (float) bgr[2] / 255.0f;
                    int idx = y * IMAGE_SIZE + x;
                    pixels[idx] = (r - MEAN[0]) / STD[0];                 // C=0
                    pixels[IMAGE_SIZE * IMAGE_SIZE + idx] = (g - MEAN[1]) / STD[1]; // C=1
                    pixels[2 * IMAGE_SIZE * IMAGE_SIZE + idx] = (b - MEAN[2]) / STD[2]; // C=2
                }
            }
            resized.release();
            return pixels;
        } finally {
            src.release();
        }
    }

    /**
     * decoder 自回归生成。
     *
     * @param encoderHidden encoder hidden state（行 = token）
     * @return 生成的文本
     * @throws Exception 生成异常
     */
    private String generate(float[][] encoderHidden) throws Exception {
        int seqLen = encoderHidden.length;
        int hiddenSize = encoderHidden[0].length;
        long[] encShape = {1, seqLen, hiddenSize};
        float[] encFlat = new float[seqLen * hiddenSize];
        for (int i = 0; i < seqLen; i++) {
            System.arraycopy(encoderHidden[i], 0, encFlat, i * hiddenSize, hiddenSize);
        }
        List<Long> tokens = new ArrayList<>();
        tokens.add(BOS_ID);

        try (OnnxTensor encoderTensor = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(encFlat), encShape)) {
            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                long[] inputIds = new long[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) {
                    inputIds[i] = tokens.get(i);
                }
                long[] idsShape = {1, inputIds.length};
                try (OnnxTensor idsTensor = OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(inputIds), idsShape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("input_ids", idsTensor);
                    inputs.put("encoder_hidden_states", encoderTensor);
                    try (OrtSession.Result result = decoderSession.run(inputs)) {
                        OnnxTensor logitsTensor = (OnnxTensor) result.get("logits").get();
                        float[] logits = logitsTensor.getFloatBuffer().array();
                        long[] logitsShape = logitsTensor.getInfo().getShape();
                        int dSeqLen = (int) logitsShape[1];
                        int vocabSize = (int) logitsShape[2];
                        int offset = (dSeqLen - 1) * vocabSize;
                        int nextToken = argmax(logits, offset, vocabSize);
                        if (nextToken == EOS_ID) {
                            break;
                        }
                        tokens.add((long) nextToken);
                    }
                }
            }
        }

        long[] finalIds = new long[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            finalIds[i] = tokens.get(i);
        }
        try {
            return tokenizer.decode(finalIds);
        } catch (Exception e) {
            log.warn("[ViT-GPT2] decode 失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * argmax 取最大概率 token。
     *
     * @param logits   全部分数
     * @param offset   起始偏移
     * @param vocabSize 词表大小
     * @return token id
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
     * 关闭资源。
     */
    public void close() {
        if (encoderSession != null) {
            try {
                encoderSession.close();
            } catch (Exception ignored) {
            }
        }
        if (decoderSession != null) {
            try {
                decoderSession.close();
            } catch (Exception ignored) {
            }
        }
        encoderSession = null;
        decoderSession = null;
        tokenizer = null;
        prepared = false;
    }
}
