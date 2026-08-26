package com.chua.deeplearning.support.onnx.generation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.DeviceSelector;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;
import java.util.Set;

/**
 * Small Stable Diffusion v0 文生图全流程编排（文本编码 → CFG 引导 DDIM 去噪 → VAE 解码）。
 *
 * <p><b>原生 ORT 实现</b>：三个阶段均通过 ai.onnxruntime 原生会话执行，
 * 不经过 DJL 模型包装——规避该导出版本文本编码器输出 uint32 张量、
 * DJL NDArray 不支持的问题；文本编码阶段仅请求 fp32 输出。</p>
 *
 * <p>设备策略：跟随 {@link DeviceSelector}——auto 模式探测到可用 GPU 时
 * 各会话启用 CUDA EP，任一会话初始化失败自动整体降级 CPU（粘性）。
 * 权重与 tokenizer.json 缺失时自动下载（hf-mirror.com 镜像优先）。</p>
 *
 * <p>采样：DDIM η=0（scaled_linear β ∈ [0.00085, 0.012]，1000 训练步）；
 * CFG 默认 7.5。可调系统属性：{@code small.sd.steps}、{@code small.sd.guidance}、
 * {@code small.sd.seed}、{@code small.sd.width}、{@code small.sd.height}、
 * {@code deeplearning.device}。</p>
 *
 * <p>权重来源：{@code subpixel/small-stable-diffusion-v0-onnx-ort-web}
 * （OFA-Sys/small-stable-diffusion-v0 的 ONNX 转换；UNet 权重外置 weights.pb）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SmallStableDiffusionCombinedTranslator implements ITranslator<Object, Object>, AutoCloseable {

    /**
     * 模型标识（与注册表一致）
     */
    public static final String MODEL_ID = "small-stable-diffusion-combined";

    /**
     * CLIP 序列长度
     */
    private static final int MAX_SEQUENCE_LENGTH = 77;

    /**
     * CLIP 填充 token ID（&lt;|endoftext|&gt;）
     */
    private static final long PAD_TOKEN_ID = 49407L;

    /**
     * latent 通道数
     */
    private static final int LATENT_CHANNELS = 4;

    /**
     * VAE 缩放因子
     */
    private static final float VAE_SCALE_FACTOR = 0.18215f;

    /**
     * 训练总扩散步数
     */
    private static final int TRAIN_TIMESTEPS = 1000;

    /**
     * 权重仓库基础地址（HuggingFace）
     */
    private static final String HF_BASE =
            "https://huggingface.co/subpixel/small-stable-diffusion-v0-onnx-ort-web/resolve/main";

    /**
     * CLIP tokenizer.json 来源（各 OpenAI CLIP 变体共享同一 BPE 词表）
     */
    private static final String TOKENIZER_URL =
            "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/tokenizer.json";

    /**
     * 图像宽度
     */
    private final int width;

    /**
     * 图像高度
     */
    private final int height;

    /**
     * 去噪步数
     */
    private final int numInferenceSteps;

    /**
     * CFG 引导系数
     */
    private final double guidanceScale;

    /**
     * 随机源
     */
    private final Random random;

    /**
     * α̅ 查找表（cumprod(1-β)），长度 1000
     */
    private final double[] alphasCumprod;

    /**
     * ORT 环境
     */
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();

    /**
     * 初始化锁
     */
    private final Object lock = new Object();

    /**
     * 是否已初始化
     */
    private volatile boolean initialized;

    /**
     * GPU 失败后强制 CPU（粘性）
     */
    private volatile boolean forceCpu;

    /**
     * 文本编码会话
     */
    private OrtSession textEncoderSession;

    /**
     * 文本编码输出名（fp32 的 hidden state）
     */
    private String textEncoderOutput;

    /**
     * UNet 会话
     */
    private OrtSession unetSession;

    /**
     * VAE 解码会话
     */
    private OrtSession vaeSession;

    /**
     * 分词器（懒加载）
     */
    private volatile ai.djl.huggingface.tokenizers.HuggingFaceTokenizer tokenizer;

    /**
     * 默认构造（512×512，20 步，引导 7.5）。
     */
    public SmallStableDiffusionCombinedTranslator() {
        this(512, 512,
                Integer.getInteger("small.sd.steps", 20),
                Double.parseDouble(System.getProperty("small.sd.guidance", "7.5")));
    }

    /**
     * 全参构造。
     *
     * @param width             宽度
     * @param height            高度
     * @param numInferenceSteps 步数
     * @param guidanceScale     CFG 引导系数
     */
    public SmallStableDiffusionCombinedTranslator(int width, int height,
                                                  int numInferenceSteps, double guidanceScale) {
        this.width = width;
        this.height = height;
        this.numInferenceSteps = Math.max(1, numInferenceSteps);
        this.guidanceScale = guidanceScale;
        Long seed = Long.getLong("small.sd.seed");
        this.random = seed != null ? new Random(seed) : new Random();
        this.alphasCumprod = buildAlphasCumprod();
        log.info("[Small SD v0][编排] 参数: {}x{}, 步数={}, 引导={}",
                width, height, this.numInferenceSteps, this.guidanceScale);
    }

    /**
     * 构建 scaled_linear β 调度的 α̅ 表（与 diffusers 默认一致）。
     *
     * @return α̅ 数组
     */
    private static double[] buildAlphasCumprod() {
        double betaStart = 0.00085d;
        double betaEnd = 0.012d;
        double[] betas = new double[TRAIN_TIMESTEPS];
        for (int i = 0; i < TRAIN_TIMESTEPS; i++) {
            double t0 = Math.sqrt(betaStart);
            double t1 = Math.sqrt(betaEnd);
            betas[i] = t0 + (t1 - t0) * i / (TRAIN_TIMESTEPS - 1);
            betas[i] *= betas[i];
        }
        double[] cumprod = new double[TRAIN_TIMESTEPS];
        double acc = 1.0d;
        for (int i = 0; i < TRAIN_TIMESTEPS; i++) {
            acc *= (1.0d - betas[i]);
            cumprod[i] = acc;
        }
        return cumprod;
    }

    /**
     * 翻译器名称。
     *
     * @return 模型标识
     */
    @Override
    public String name() {
        return MODEL_ID;
    }

    /**
     * 全流程执行：分词 → 文本编码 → DDIM 去噪 → VAE 解码 → PNG 字节。
     *
     * @param input 提示词（String）
     * @return PNG 图像字节数组
     */
    @Override
    public Object translate(Object input) {
        try {
            ensureInitialized();
            String prompt = input instanceof String s ? s : String.valueOf(input);
            long[][] idsPair = tokenizePair(prompt);

            float[] condEmb = runTextEncoder(idsPair[1]);
            float[] uncondEmb = runTextEncoder(idsPair[0]);

            int latentH = height / 8;
            int latentW = width / 8;
            float[] latent = new float[LATENT_CHANNELS * latentH * latentW];
            for (int i = 0; i < latent.length; i++) {
                latent[i] = (float) random.nextGaussian();
            }

            long[] timesteps = new long[numInferenceSteps];
            int denom = Math.max(1, numInferenceSteps - 1);
            for (int i = 0; i < numInferenceSteps; i++) {
                timesteps[i] = Math.round((TRAIN_TIMESTEPS - 1) * (1.0d - (double) i / denom));
            }

            for (int i = 0; i < numInferenceSteps; i++) {
                long t = timesteps[i];
                long tPrev = i + 1 < numInferenceSteps ? timesteps[i + 1] : 0L;
                float[] epsUncond = predictNoise(latent, t, uncondEmb);
                float[] epsCond = predictNoise(latent, t, condEmb);
                for (int k = 0; k < latent.length; k++) {
                    float eps = epsUncond[k] + (float) guidanceScale * (epsCond[k] - epsUncond[k]);
                    double acpT = alphasCumprod[(int) t];
                    double acpPrev = alphasCumprod[(int) tPrev];
                    float predX0 = (float) ((latent[k] - eps * Math.sqrt(Math.max(0d, 1d - acpT))) / Math.sqrt(acpT));
                    latent[k] = (float) (predX0 * Math.sqrt(acpPrev)
                            + eps * Math.sqrt(Math.max(0d, 1d - acpPrev)));
                }
                if (log.isDebugEnabled() && (i % 5 == 0 || i == numInferenceSteps - 1)) {
                    log.debug("[Small SD v0][编排] 去噪 {}/{} t={} -> t'={}", i + 1, numInferenceSteps, t, tPrev);
                }
            }

            return encodePng(decodeVae(latent));
        } catch (OrtException | IOException e) {
            throw new RuntimeException("Small SD 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 懒加载：确保权重就绪并打开三个会话（GPU 失败整体降级 CPU）。
     *
     * @throws OrtException 会话异常
     * @throws IOException  权重缺失
     */
    private void ensureInitialized() throws OrtException, IOException {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            Path base = resolveBaseDir();
            Path tePath = base.resolve("text_encoder").resolve("model.onnx");
            // 主模型文件可能以扁平缓存形态存在（registry 下载产物），迁移为结构化布局
            migrateFlatPrimary(base, tePath);
            ensureFile(tePath, HF_BASE + "/text_encoder/model.onnx");
            ensureFile(base.resolve("unet").resolve("model.onnx"), HF_BASE + "/unet/model.onnx");
            // UNet ONNX 引用的外部数据文件（>2GB 导出拆分），必须同目录
            ensureFile(base.resolve("unet").resolve("weights.pb"), HF_BASE + "/unet/weights.pb");
            ensureFile(base.resolve("vae_decoder").resolve("model.onnx"), HF_BASE + "/vae_decoder/model.onnx");
            ensureTokenizer(base);

            boolean wantGpu = !forceCpu && DeviceSelector.resolve(deviceSetting()).equals("gpu");
            try {
                openSessions(base, wantGpu);
                if (wantGpu) {
                    log.info("[Small SD v0][编排] 设备: GPU (CUDA EP)");
                }
            } catch (OrtException | UnsatisfiedLinkError e) {
                if (!wantGpu) {
                    throw e;
                }
                log.warn("[Small SD v0][编排] GPU 会话创建失败，自动降级 CPU: {}", e.getMessage());
                forceCpu = true;
                closeSessions();
                openSessions(base, false);
            }
            initialized = true;
        }
    }

    /**
     * 当前生效的设备设置来源。
     *
     * @return 设备设置字符串
     */
    private String deviceSetting() {
        return System.getProperty("deeplearning.device");
    }

    /**
     * 打开三个会话。
     *
     * @param base   权重目录
     * @param useGpu 是否启用 CUDA EP
     * @throws OrtException 会话创建失败
     */
    private void openSessions(Path base, boolean useGpu) throws OrtException, IOException {
        textEncoderSession = openSession(base.resolve("text_encoder").resolve("model.onnx"), useGpu);
        textEncoderOutput = pickTextEncoderOutput(textEncoderSession);
        unetSession = openSession(base.resolve("unet").resolve("model.onnx"), useGpu);
        vaeSession = openSession(base.resolve("vae_decoder").resolve("model.onnx"), useGpu);
        log.info("[Small SD v0][编排] 三个会话已打开 ({})", useGpu ? "GPU" : "CPU");
    }

    /**
     * 创建单个会话。
     *
     * @param path   ONNX 路径
     * @param useGpu 是否 CUDA
     * @return 会话
     * @throws OrtException 创建失败
     */
    private OrtSession openSession(Path path, boolean useGpu) throws OrtException {
        OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
        if (useGpu) {
            opt.addCUDA(0);
        }
        return env.createSession(path.toString(), opt);
    }

    /**
     * 选择文本编码器的 fp32 输出（避开 uint32 类型的附加输出）。
     *
     * @param session 文本编码会话
     * @return 输出名
     */
    private String pickTextEncoderOutput(OrtSession session) {
        Set<String> names = session.getOutputNames();
        for (String n : names) {
            if (n.toLowerCase().contains("hidden")) {
                return n;
            }
        }
        return names.iterator().next();
    }

    /**
     * 迁移 registry 扁平缓存产物到结构化布局（幂等）。
     *
     * @param base   权重基准目录
     * @param tePath 文本编码器目标路径
     * @throws IOException 移动失败
     */
    private void migrateFlatPrimary(Path base, Path tePath) throws IOException {
        Path flat = base.resolve(MODEL_ID).resolve("model.onnx");
        if (Files.exists(flat) && !Files.exists(tePath)) {
            Files.createDirectories(tePath.getParent());
            Files.move(flat, tePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Small SD v0][编排] 已迁移主模型缓存: {} -> {}", flat, tePath);
        }
    }

    /**
     * 解析权重基准目录（registry 缓存根或配置路径）。
     *
     * @return 基准目录
     */
    private Path resolveBaseDir() {
        Path cacheRoot = java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"),
                "chua-dl-models", "download");
        Path configured = ModelRegistry.resolveConfiguredPath("vision/detection/small-sd");
        return configured != null ? configured : cacheRoot;
    }

    /**
     * 确保目标文件存在，缺失时下载（镜像优先，带超时）。
     *
     * @param target 目标路径
     * @param remote 远程地址
     * @throws IOException 失败
     */
    private void ensureFile(Path target, String remote) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        Files.createDirectories(target.getParent());
        String mirror = remote.replace("huggingface.co", "hf-mirror.com");
        Exception last = null;
        for (String url : new String[]{mirror, remote}) {
            try {
                log.info("[Small SD v0][编排] 下载权重: {} -> {}", url, target);
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(180_000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    throw new IOException("HTTP " + code);
                }
                try (var in = conn.getInputStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    conn.disconnect();
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception e) {
                last = e;
                log.warn("[Small SD v0][编排] 下载失败（{}）: {}", url, e.getMessage());
            }
        }
        throw new IOException("权重缺失且下载失败: " + target, last);
    }

    /**
     * 确保 tokenizer.json 就绪。
     *
     * @param base 基准目录
     * @throws IOException 失败
     */
    private void ensureTokenizer(Path base) throws IOException {
        if (Files.exists(base.resolve("tokenizer.json"))) {
            return;
        }
        ensureFile(base.resolve("tokenizer.json"), TOKENIZER_URL);
    }

    /**
     * 编码正/空负提示词对。
     *
     * @param prompt 正向提示词
     * @return [无条件ids, 条件ids]
     * @throws IOException 分词失败
     */
    private long[][] tokenizePair(String prompt) throws IOException {
        return new long[][]{tokenize(""), tokenize(prompt)};
    }

    /**
     * 定长分词（截断/填充到 77）。
     *
     * @param text 文本
     * @return token ids
     * @throws IOException 失败
     */
    private long[] tokenize(String text) throws IOException {
        if (tokenizer == null) {
            synchronized (lock) {
                if (tokenizer == null) {
                    Path p = weightDir().resolve("tokenizer.json");
                    tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                            .optTokenizerPath(p)
                            .optTruncation(true)
                            .build();
                }
            }
        }
        long[] ids = tokenizer.encode(text == null ? "" : text).getIds();
        long[] fixed = new long[MAX_SEQUENCE_LENGTH];
        System.arraycopy(ids, 0, fixed, 0, Math.min(ids.length, MAX_SEQUENCE_LENGTH));
        java.util.Arrays.fill(fixed, Math.min(ids.length, MAX_SEQUENCE_LENGTH), MAX_SEQUENCE_LENGTH, PAD_TOKEN_ID);
        return fixed;
    }

    /**
     * 权重目录（延迟定位）。
     *
     * @return 目录
     */
    private Path weightDir() {
        Path configured = ModelRegistry.resolveConfiguredPath("vision/detection/small-sd/tokenizer.json");
        if (configured != null) {
            return configured.getParent();
        }
        return resolveBaseDir();
    }

    /**
     * 文本编码前向（仅请求 fp32 hidden state 输出）。
     *
     * @param ids token ids（长度 77）
     * @return 嵌入 [77*768]
     * @throws OrtException 推理失败
     */
    private float[] runTextEncoder(long[] ids) throws OrtException {
        int[][] ids32 = new int[1][ids.length];
        for (int i = 0; i < ids.length; i++) {
            ids32[0][i] = (int) ids[i];
        }
        try (OnnxTensor t = OnnxTensor.createTensor(env, ids32);
             OrtSession.Result result = textEncoderSession.run(
                     java.util.Map.of("input_ids", t),
                     Set.of(textEncoderOutput))) {
            float[][][] out = (float[][][]) result.get(0).getValue();
            float[] flat = new float[out[0].length * out[0][0].length];
            int k = 0;
            for (float[] row : out[0]) {
                for (float v : row) {
                    flat[k++] = v;
                }
            }
            return flat;
        }
    }

    /**
     * 单次 UNet 噪声预测。
     *
     * @param latent 当前 latent
     * @param t      时间步
     * @param emb    文本嵌入
     * @return 噪声预测
     * @throws OrtException 推理失败
     */
    private float[] predictNoise(float[] latent, long t, float[] emb) throws OrtException {
        int latentH = height / 8;
        int latentW = width / 8;
        float[][][][] sample = new float[1][LATENT_CHANNELS][latentH][latentW];
        int idx = 0;
        for (int c = 0; c < LATENT_CHANNELS; c++) {
            for (int h = 0; h < latentH; h++) {
                for (int w = 0; w < latentW; w++) {
                    sample[0][c][h][w] = latent[idx++];
                }
            }
        }
        float[][][][] hidden = new float[1][1][MAX_SEQUENCE_LENGTH][emb.length / MAX_SEQUENCE_LENGTH];
        int dim = emb.length / MAX_SEQUENCE_LENGTH;
        for (int i = 0; i < MAX_SEQUENCE_LENGTH; i++) {
            System.arraycopy(emb, i * dim, hidden[0][0][i], 0, dim);
        }

        try (OnnxTensor x = OnnxTensor.createTensor(env, sample);
             OnnxTensor tF = OnnxTensor.createTensor(env, new float[]{(float) t});
             OnnxTensor e = OnnxTensor.createTensor(env, hidden);
             OrtSession.Result r = unetSession.run(java.util.Map.of(
                     "sample", x, "timestep", tF, "encoder_hidden_states", e))) {
            float[][][][] out = (float[][][][]) r.get(0).getValue();
            float[] flat = new float[LATENT_CHANNELS * latentH * latentW];
            int k = 0;
            for (int c = 0; c < LATENT_CHANNELS; c++) {
                for (int h = 0; h < latentH; h++) {
                    for (int w = 0; w < latentW; w++) {
                        flat[k++] = out[0][c][h][w];
                    }
                }
            }
            return flat;
        } catch (OrtException ex) {
            // FLOAT32 时间步不被接受时回退 INT64 重试一次
            try (OnnxTensor x = OnnxTensor.createTensor(env, sample);
                 OnnxTensor tI = OnnxTensor.createTensor(env, new long[]{t});
                 OnnxTensor e = OnnxTensor.createTensor(env, hidden);
                 OrtSession.Result r = unetSession.run(java.util.Map.of(
                         "sample", x, "timestep", tI, "encoder_hidden_states", e))) {
                float[][][][] out = (float[][][][]) r.get(0).getValue();
                float[] flat = new float[LATENT_CHANNELS * latentH * latentW];
                int k = 0;
                for (int c = 0; c < LATENT_CHANNELS; c++) {
                    for (int h = 0; h < latentH; h++) {
                        for (int w = 0; w < latentW; w++) {
                            flat[k++] = out[0][c][h][w];
                        }
                    }
                }
                return flat;
            }
        }
    }

    /**
     * VAE 解码 latent 为 RGB 像素。
     *
     * @param latent 最终 latent
     * @return RGB 像素（行优先）
     * @throws OrtException 推理失败
     */
    private int[] decodeVae(float[] latent) throws OrtException {
        int latentH = height / 8;
        int latentW = width / 8;
        float[][][][] sample = new float[1][LATENT_CHANNELS][latentH][latentW];
        int idx = 0;
        for (int c = 0; c < LATENT_CHANNELS; c++) {
            for (int h = 0; h < latentH; h++) {
                for (int w = 0; w < latentW; w++) {
                    sample[0][c][h][w] = latent[idx++] / VAE_SCALE_FACTOR;
                }
            }
        }
        try (OnnxTensor t = OnnxTensor.createTensor(env, sample);
             OrtSession.Result r = vaeSession.run(java.util.Map.of("latent_sample", t))) {
            float[][][][] out = (float[][][][]) r.get(0).getValue();
            int imgH = out[0][0].length;
            int imgW = out[0][0][0].length;
            int[] rgb = new int[imgH * imgW];
            for (int h = 0; h < imgH; h++) {
                for (int w = 0; w < imgW; w++) {
                    float rr = out[0][0][h][w];
                    float gg = out[0][1][h][w];
                    float bb = out[0][2][h][w];
                    int ri = clamp255((rr + 1f) * 127.5f);
                    int gi = clamp255((gg + 1f) * 127.5f);
                    int bi = clamp255((bb + 1f) * 127.5f);
                    rgb[h * imgW + w] = (ri << 16) | (gi << 8) | bi;
                }
            }
            return rgb;
        }
    }

    /**
     * 数值截断到 [0,255]。
     *
     * @param v 原值
     * @return 整数像素值
     */
    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /**
     * 像素编码为 PNG 字节。
     *
     * @param rgb 行优先像素
     * @return PNG bytes
     * @throws IOException 编码失败
     */
    private byte[] encodePng(int[] rgb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, rgb, 0, width);
        try (var bos = new java.io.ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bos);
            return bos.toByteArray();
        }
    }

    /**
     * 关闭全部会话。
     */
    @Override
    public void close() {
        closeSessions();
    }

    /**
     * 关闭会话（静默）。
     */
    private void closeSessions() {
        for (OrtSession s : new OrtSession[]{textEncoderSession, unetSession, vaeSession}) {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        textEncoderSession = null;
        unetSession = null;
        vaeSession = null;
        initialized = false;
    }
}
