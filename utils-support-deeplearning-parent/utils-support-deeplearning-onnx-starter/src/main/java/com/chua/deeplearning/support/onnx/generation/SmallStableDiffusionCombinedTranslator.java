package com.chua.deeplearning.support.onnx.generation;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;

/**
 * Small Stable Diffusion v0 文生图全流程编排（文本编码 → DDIM 去噪 → VAE 解码）。
 *
 * <p>注册表主模型为<b>文本编码器</b> ONNX；UNet 与 VAE 解码器由本类在首次推理时
 * 通过 DJL {@link Criteria} 加载并真实执行会话。三个模型文件与 tokenizer.json
 * 缺失时自动从 HuggingFace 下载（自动尝试 hf-mirror.com 国内镜像）。</p>
 *
 * <p>流程：
 * <ol>
 *   <li>CLIP tokenizer 编码正向/空负向提示词 → [2,77] token 对</li>
 *   <li>文本编码器一次前向得到条件/无条件嵌入 [2,77,768]</li>
 *   <li>DDIM 确定性采样（scaled_linear β ∈ [0.00085, 0.012]，1000 训练步），
 *       每步分别前向无条件/条件分支并按 guidance 融合</li>
 *   <li>VAE 解码 latent（÷0.18215）→ 512×512 图像</li>
 * </ol>
 *
 * <p>设备策略：跟随 {@link com.chua.deeplearning.support.engine.DeviceSelector}——
 * auto 模式探测到可用 GPU 时走 CUDA EP，加载失败自动降级 CPU。
 *
 * <p>可调参数（系统属性）：{@code small.sd.steps}（默认 20）、
 * {@code small.sd.guidance}（默认 7.5）、{@code small.sd.seed}（默认随机）、
 * {@code deeplearning.device}（auto/cpu/gpu）。
 *
 * <p>权重来源：{@code subpixel/small-stable-diffusion-v0-onnx-ort-web}
 * （OFA-Sys/small-stable-diffusion-v0 的 ONNX 转换，fp32 约 3GB；
 * UNet 含外部数据文件 weights.pb，已一并下载）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SmallStableDiffusionCombinedTranslator implements Translator<String, Image> {

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
     * 默认图像宽度
     */
    private final int width;

    /**
     * 默认图像高度
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
     * UNet 推理模型（懒加载）
     */
    private volatile ZooModel<NDList, NDList> unetModel;

    /**
     * 权重基准目录（prepare 阶段确定，供分词器定位使用）
     */
    private volatile Path weightBaseDir;

    /**
     * VAE 解码推理模型（懒加载）
     */
    private volatile ZooModel<NDList, Image> vaeModel;

    /**
     * 使用默认参数构造。
     */
    public SmallStableDiffusionCombinedTranslator() {
        this(512, 512,
                Integer.getInteger("small.sd.steps", 20),
                Double.parseDouble(System.getProperty("small.sd.guidance", "7.5")));
    }

    /**
     * 全参构造。
     *
     * @param width             输出宽度（8 的倍数）
     * @param height            输出高度（8 的倍数）
     * @param numInferenceSteps 去噪步数
     * @param guidanceScale     CFG 引导系数（≤1 时禁用引导）
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
        log.info("[Small SD v0][编排] 参数: {}x{}, 步数={}, 引导={}", width, height,
                this.numInferenceSteps, this.guidanceScale);
    }

    /**
     * 构建 scaled_linear β 调度的 α̅ 表。
     *
     * <p>β_t = linspace(√0.00085, √0.012, 1000)²，α̅_t = Π(1-β)，与 diffusers 默认一致。</p>
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
     * 加载 UNet / VAE 解码模型并确保权重文件就绪。
     *
     * @param ctx 推理上下文
     * @throws IOException 模型准备失败
     */
    @Override
    public void prepare(ai.djl.translate.TranslatorContext ctx) throws IOException {
        // 主模型即文本编码器；其所在目录的父目录作为权重基准目录
        Path basePath = resolveBaseDir(ctx);
        log.info("[Small SD v0][编排] 权重目录: {}", basePath);

        Path unetPath = ensureFile(basePath.resolve("unet").resolve("model.onnx"),
                HF_BASE + "/unet/model.onnx");
        // UNet ONNX 引用的外部数据文件（>2GB 导出拆分），必须与 model.onnx 同目录
        ensureFile(basePath.resolve("unet").resolve("weights.pb"),
                HF_BASE + "/unet/weights.pb");
        Path vaePath = ensureFile(basePath.resolve("vae_decoder").resolve("model.onnx"),
                HF_BASE + "/vae_decoder/model.onnx");
        ensureTokenizer(basePath);
        this.weightBaseDir = basePath;

        if (unetModel == null) {
            synchronized (this) {
                if (unetModel == null) {
                    unetModel = loadWithDeviceFallback(
                            unetPath, new SmallSdUnetTranslator(width, height),
                            NDList.class, NDList.class);
                    log.info("[Small SD v0][编排] UNet 已加载");
                }
            }
        }
        if (vaeModel == null) {
            synchronized (this) {
                if (vaeModel == null) {
                    vaeModel = loadWithDeviceFallback(
                            vaePath, new SmallSdVaeDecoderTranslator(width, height),
                            NDList.class, Image.class);
                    log.info("[Small SD v0][编排] VAE 解码器已加载");
                }
            }
        }
    }

    /**
     * 解析权重基准目录。
     *
     * <p>优先取主模型（文本编码器）文件所在目录的父目录；不可用时回退到注册表解析路径。</p>
     *
     * @param ctx 推理上下文
     * @return 基准目录
     * @throws IOException 目录无法确定
     */
    private Path resolveBaseDir(ai.djl.translate.TranslatorContext ctx) throws IOException {
        try {
            Path modelPath = ctx.getModel().getModelPath();
            if (modelPath != null && Files.exists(modelPath)) {
                Path dir = Files.isRegularFile(modelPath)
                        ? modelPath.getParent().getParent()
                        : modelPath.getParent();
                if (dir != null && Files.isDirectory(dir)) {
                    return dir;
                }
            }
        } catch (Exception e) {
            log.debug("[Small SD v0][编排] 主模型路径不可用: {}", e.getMessage());
        }
        Path fallback = com.chua.deeplearning.support.engine.ModelRegistry
                .resolveConfiguredPath("vision/detection/small-sd");
        if (fallback != null) {
            return fallback;
        }
        throw new IOException("无法定位 small-sd 权重目录，请确认模型已下载或配置 "
                + "deeplearning.model.root-dir");
    }

    /**
     * 确保目标文件存在，缺失时从远程下载（自动切换 hf-mirror.com 镜像）。
     *
     * @param target 本地目标路径
     * @param remote 远程地址
     * @return 就绪的本地文件
     * @throws IOException 下载失败且本地缺失
     */
    private Path ensureFile(Path target, String remote) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }
        Files.createDirectories(target.getParent());
        String mirror = remote.replace("huggingface.co", "hf-mirror.com");
        Exception last = null;
        for (String url : new String[]{remote, mirror}) {
            try {
                log.info("[Small SD v0][编排] 下载权重: {} -> {}", url, target);
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                try (var in = new URL(url).openStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (Exception e) {
                last = e;
                log.warn("[Small SD v0][编排] 下载失败（{}）: {}", url, e.getMessage());
            }
        }
        throw new IOException("权重文件缺失且下载失败: " + target + "（可手动从 " + remote + " 下载放置）", last);
    }

    /**
     * 确保 tokenizer.json 就绪。
     *
     * @param basePath 权重基准目录
     * @throws IOException 下载失败且本地缺失
     */
    private void ensureTokenizer(Path basePath) throws IOException {
        if (Files.exists(basePath.resolve("tokenizer.json"))) {
            return;
        }
        ensureFile(basePath.resolve("tokenizer.json"), TOKENIZER_URL);
    }

    /**
     * 按全局设备策略加载模型：auto 探测可用 GPU 时走 CUDA EP，失败自动降级 CPU。
     *
     * @param path       ONNX 路径
     * @param translator 配套 Translator
     * @param inputType  输入类型
     * @param outputType 输出类型
     * @param <I>        输入泛型
     * @param <O>        输出泛型
     * @return ZooModel
     * @throws IOException 加载失败（含降级后仍失败）
     */
    private static <I, O> ZooModel<I, O> loadWithDeviceFallback(Path path, Translator<I, O> translator,
                                                                Class<I> inputType, Class<O> outputType)
            throws IOException {
        boolean wantGpu = com.chua.deeplearning.support.engine.DeviceSelector.resolve(null).equals("gpu");
        try {
            return buildCriteria(path, translator, inputType, outputType,
                    wantGpu ? ai.djl.Device.gpu() : ai.djl.Device.cpu());
        } catch (IOException e) {
            if (!wantGpu) {
                throw e;
            }
            log.warn("[Small SD v0][编排] GPU 加载失败，自动降级 CPU: {}", e.getMessage());
            return buildCriteria(path, translator, inputType, outputType, ai.djl.Device.cpu());
        }
    }

    /**
     * 构建并加载指定设备的 Criteria。
     *
     * @param path       ONNX 路径
     * @param translator 配套 Translator
     * @param inputType  输入类型
     * @param outputType 输出类型
     * @param device     目标设备
     * @param <I>        输入泛型
     * @param <O>        输出泛型
     * @return ZooModel
     * @throws IOException 加载失败
     */
    private static <I, O> ZooModel<I, O> buildCriteria(Path path, Translator<I, O> translator,
                                                       Class<I> inputType, Class<O> outputType,
                                                       ai.djl.Device device) throws IOException {
        Criteria<I, O> criteria = Criteria.builder()
                .setTypes(inputType, outputType)
                .optModelPath(path)
                .optEngine("OnnxRuntime")
                .optDevice(device)
                .optTranslator(translator)
                .build();
        return loadQuietly(criteria);
    }

    /**
     * 加载 Criteria 并将受检异常统一转译为 IOException。
     *
     * @param criteria DJL Criteria
     * @param <I>      输入类型
     * @param <O>      输出类型
     * @return ZooModel
     * @throws IOException 加载失败
     */
    private static <I, O> ZooModel<I, O> loadQuietly(Criteria<I, O> criteria) throws IOException {
        try {
            return criteria.loadModel();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("模型加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 文本编码阶段输入：同时编码正向与空负向提示词并堆叠为 batch=2。
     *
     * @param ctx    推理上下文
     * @param prompt 正向提示词
     * @return input_ids [2,77]
     */
    @Override
    public NDList processInput(ai.djl.translate.TranslatorContext ctx, String prompt) throws Exception {
        var manager = ctx.getNDManager();
        long[] condIds = tokenize(prompt);
        long[] uncondIds = tokenize("");

        var ids = manager.create(new long[][]{condIds, uncondIds});
        ids.setName("input_ids");
        return new NDList(ids);
    }

    /**
     * 单条提示词定长分词（截断/填充到 77）。
     *
     * @param text 提示词
     * @return 定长 token ID 数组
     * @throws IOException 分词失败
     */
    private long[] tokenize(String text) throws IOException {
        Path tokenizerPath = locateTokenizer();
        try (var tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optTruncation(true)
                .build()) {
            var encoding = tokenizer.encode(text == null ? "" : text);
            long[] ids = encoding.getIds();
            if (ids.length > MAX_SEQUENCE_LENGTH) {
                long[] clipped = new long[MAX_SEQUENCE_LENGTH];
                System.arraycopy(ids, 0, clipped, 0, MAX_SEQUENCE_LENGTH);
                return clipped;
            }
            long[] padded = new long[MAX_SEQUENCE_LENGTH];
            System.arraycopy(ids, 0, padded, 0, ids.length);
            java.util.Arrays.fill(padded, ids.length, MAX_SEQUENCE_LENGTH, PAD_TOKEN_ID);
            return padded;
        }
    }

    /**
     * 定位 tokenizer.json（优先权重基准目录，其次注册表配置路径）。
     *
     * @return tokenizer 路径
     * @throws IOException 找不到文件
     */
    private Path locateTokenizer() throws IOException {
        Path base = weightBaseDir;
        if (base != null && Files.exists(base.resolve("tokenizer.json"))) {
            return base.resolve("tokenizer.json");
        }
        Path configured = com.chua.deeplearning.support.engine.ModelRegistry
                .resolveConfiguredPath("vision/detection/small-sd/tokenizer.json");
        if (configured != null) {
            return configured;
        }
        throw new IOException("未找到 tokenizer.json（预期位于 small-sd 权重根目录: " + base + "）");
    }
        throw new IOException("未找到 tokenizer.json（预期位于 small-sd 权重根目录）");
    }

    /**
     * 去噪与解码阶段：文本嵌入 → CFG 引导 DDIM 循环 → VAE 出图。
     *
     * @param ctx        推理上下文
     * @param embeddings 文本编码输出 [2,77,768]（0=无条件, 1=条件）
     * @return 生成图像
     */
    @Override
    public Image processOutput(ai.djl.translate.TranslatorContext ctx, NDList embeddings) throws Exception {
        var manager = ctx.getNDManager();
        NDArray allEmb = embeddings.singletonOrThrow();

        // batch=2 沿轴 0 拆分：0=无条件（空提示词），1=条件（正向提示词）。
        // 通过堆内存拷贝拆分，避免依赖特定 DJL 版本的切片 API。
        float[] all = allEmb.toFloatArray();
        int perBatch = all.length / 2;
        float[] uncondArr = new float[perBatch];
        float[] condArr = new float[perBatch];
        System.arraycopy(all, 0, uncondArr, 0, perBatch);
        System.arraycopy(all, perBatch, condArr, 0, perBatch);
        Shape embShape = new Shape(1, MAX_SEQUENCE_LENGTH, perBatch / MAX_SEQUENCE_LENGTH);
        NDArray uncondEmb = manager.create(uncondArr, embShape);
        NDArray condEmb = manager.create(condArr, embShape);

        // 初始噪声 latent [1,4,H/8,W/8]
        int latentH = height / 8;
        int latentW = width / 8;
        float[] noise = new float[LATENT_CHANNELS * latentH * latentW];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (float) random.nextGaussian();
        }
        NDArray latent = manager.create(noise, new Shape(1, LATENT_CHANNELS, latentH, latentW));

        // 均匀时间步：999 → 0（两端包含，与 diffusers linspace 一致）
        long[] timestepArr = new long[numInferenceSteps];
        int denom = Math.max(1, numInferenceSteps - 1);
        for (int i = 0; i < numInferenceSteps; i++) {
            timestepArr[i] = Math.round((TRAIN_TIMESTEPS - 1) * (1.0d - (double) i / denom));
        }

        for (int i = 0; i < numInferenceSteps; i++) {
            long t = timestepArr[i];
            long tPrev = i + 1 < numInferenceSteps ? timestepArr[i + 1] : 0L;

            NDArray epsUncond = predictNoise(manager, latent, t, uncondEmb);
            NDArray epsCond = predictNoise(manager, latent, t, condEmb);
            NDArray eps = epsUncond.add(epsCond.sub(epsUncond).mul(guidanceScale));

            // DDIM 确定性更新（η=0）
            double acpT = alphasCumprod[(int) t];
            double acpPrev = alphasCumprod[(int) tPrev];
            NDArray predX0 = latent.sub(eps.mul((float) Math.sqrt(Math.max(0d, 1d - acpT))))
                    .div((float) Math.sqrt(acpT));
            latent = predX0.mul((float) Math.sqrt(acpPrev))
                    .add(eps.mul((float) Math.sqrt(Math.max(0d, 1d - acpPrev))));

            if (log.isDebugEnabled() && (i % 5 == 0 || i == numInferenceSteps - 1)) {
                log.debug("[Small SD v0][编排] 去噪 {}/{} t={} -> t'={}", i + 1, numInferenceSteps, t, tPrev);
            }
        }

        // VAE 解码（translator 内部处理 ÷0.18215 与反归一化）
        try (var predictor = vaeModel.newPredictor()) {
            return predictor.predict(new NDList(latent));
        }
    }

    /**
     * 单次 UNet 前向预测噪声。
     *
     * <p>时间步优先以 FLOAT32 传入（diffusers 官方 ONNX 导出约定），
     * 模型要求 INT64 时自动回退重试。</p>
     *
     * @param manager    NDManager
     * @param latent     当前 latent [1,4,H/8,W/8]
     * @param timestep   时间步
     * @param textEmbeds 文本嵌入 [1,77,768]
     * @return 噪声预测 [1,4,H/8,W/8]
     * @throws Exception 推理失败
     */
    private NDArray predictNoise(NDManager manager, NDArray latent,
                                 long timestep, NDArray textEmbeds) throws Exception {
        NDArray tFloat = manager.create(new float[]{(float) timestep});
        NDArray tInt = manager.create(new long[]{timestep});
        for (NDArray t : new NDArray[]{tFloat, tInt}) {
            NDList input = new NDList(latent.duplicate(), t.duplicate(), textEmbeds.duplicate());
            try (var predictor = unetModel.newPredictor()) {
                NDList out = predictor.predict(input);
                return out.singletonOrThrow();
            } catch (Exception e) {
                if (t == tInt) {
                    throw e;
                }
                log.info("[Small SD v0][编排] timestep=FLOAT32 不被接受，回退 INT64: {}", e.getMessage());
            } finally {
                input.close();
            }
        }
        throw new IllegalStateException("UNet 前向失败");
    }

    /**
     * 释放子模型资源。
     */
    public void close() {
        closeQuietly(unetModel);
        closeQuietly(vaeModel);
        unetModel = null;
        vaeModel = null;
    }

    /**
     * 静默关闭可关闭对象。
     *
     * @param closeable 目标
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}
