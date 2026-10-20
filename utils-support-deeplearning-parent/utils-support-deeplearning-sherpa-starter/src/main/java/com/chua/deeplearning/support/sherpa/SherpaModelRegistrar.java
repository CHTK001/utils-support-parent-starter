package com.chua.deeplearning.support.sherpa;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * sherpa-onnx 模型元数据注册器（默认关闭，按需开启）。
 *
 * <p>把常用 sherpa-onnx 官方模型登记到 {@link ModelRegistry}，使控制台的模型清单能直接
 * 看到条目并按 {@code downloadUrl} 下载。注意两个前置约束：</p>
 * <ol>
 *   <li>推理由 sherpa-onnx 原生层完成，本模块没有 {@code ITranslator} 实现，
 *       因此 {@code translatorClassName} 只能为 {@code null}——这条目不能通过
 *       {@code OnnxAudioClient} 之类的「translator 引擎」入口驱动；</li>
 *   <li>{@link ModelRegistry} 按文件扩展名推断 provider，{@code .onnx} 会被归到
 *       {@code onnx} 引擎名下，从而混入 onnx 的模型清单。</li>
 * </ol>
 *
 * <p>为避免污染现有 onnx 引擎的模型目录（选中后调用会因缺少 translator 失败），
 * 本注册器<b>默认不注册</b>，仅在显式设置 {@code -Dsherpa.onnx.register-models=true}
 * 时生效。绝大多数场景无需开启：把模型放进 {@code -Dsherpa.onnx.model.dir} 即可，
 * 两个 SPI 客户端的 {@code models()} 会直接枚举该目录。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SherpaModelRegistrar implements ModelRegistrar {

    /**
     * 系统属性：是否把 sherpa 模型登记进 {@link ModelRegistry}。
     */
    public static final String PROP_REGISTER_MODELS = "sherpa.onnx.register-models";

    /**
     * HuggingFace 主地址前缀（sherpa-onnx 官方模型仓库）。
     */
    private static final String HF = "https://huggingface.co/csukuangfj/";

    /**
     * HuggingFace 镜像地址前缀。
     */
    private static final String HF_MIRROR = "https://hf-mirror.com/csukuangfj/";

    /**
     * 类初始化即注册（仅当显式开启时），保证 SPI 扫描到本类时条目已就绪。
     */
    static {
        if (Boolean.getBoolean(PROP_REGISTER_MODELS)) {
            registerAll();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(ModelRegistry registry) {
        if (Boolean.getBoolean(PROP_REGISTER_MODELS)) {
            registerAll();
        }
    }

    /**
     * 注册全部 sherpa-onnx 模型元数据（已注册则跳过）。
     */
    private static void registerAll() {
        // ASR：SenseVoice 多语言（自带语种/情感/事件）
        regAsr("sherpa-sensevoice-small",
                HF + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                List.of("model.int8.onnx", "tokens.txt"),
                "SenseVoice 多语言语音识别（中/英/日/韩/粤，含情感与音频事件标签）");

        // ASR：Paraformer 中文
        regAsr("sherpa-paraformer-zh-small",
                HF + "sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx",
                List.of("model.int8.onnx", "tokens.txt"),
                "Paraformer 中文离线语音识别（非自回归，速度快）");

        // ASR：Whisper 多语言（编码器 + 解码器）
        regAsr("sherpa-whisper-tiny",
                HF + "sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                List.of("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt"),
                "Whisper tiny 多语言语音识别（编码器/解码器双模型）");

        // ASR：流式 Zipformer 中英双语（供 SherpaStreamingRecognizer 使用）
        regAsr("sherpa-streaming-zipformer-zh-en",
                HF + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
                List.of("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
                "流式中英双语 Zipformer Transducer（麦克风实时识别）");

        // TTS：中文 VITS 多说话人
        regTts("sherpa-vits-icefall-zh",
                HF + "sherpa-onnx-vits-zh-ll/resolve/main/model.onnx",
                List.of("model.onnx", "tokens.txt", "lexicon.txt", "speakers.txt"),
                "中文 VITS 语音合成（多说话人，sherpa-onnx 导出）");
    }

    /**
     * 注册一个 ASR 模型元数据。
     *
     * @param modelId  模型标识
     * @param mainUrl  主文件下载地址
     * @param files    附属文件清单（相对主文件同目录）
     * @param describe 描述
     */
    private static void regAsr(String modelId, String mainUrl, List<String> files, String describe) {
        if (ModelRegistry.get(modelId) != null) {
            return;
        }
        String fileName = lastSegment(mainUrl);
        ModelRegistry.register(modelId, null, byte[].class, String.class, VirtualClient.class,
                modelId + "/" + fileName, mainUrl, List.of(mirrorOf(mainUrl)), files, false, fileName);
        log.debug("[sherpa] 注册 ASR 模型 {} -> {}（{}）", modelId, mainUrl, describe);
    }

    /**
     * 注册一个 TTS 模型元数据。
     *
     * @param modelId  模型标识
     * @param mainUrl  主文件下载地址
     * @param files    附属文件清单
     * @param describe 描述
     */
    private static void regTts(String modelId, String mainUrl, List<String> files, String describe) {
        if (ModelRegistry.get(modelId) != null) {
            return;
        }
        String fileName = lastSegment(mainUrl);
        ModelRegistry.register(modelId, null, String.class, byte[].class, TextToAudioClient.class,
                modelId + "/" + fileName, mainUrl, List.of(mirrorOf(mainUrl)), files, false, fileName);
        log.debug("[sherpa] 注册 TTS 模型 {} -> {}（{}）", modelId, mainUrl, describe);
    }

    /**
     * 把 HuggingFace 地址转换为镜像地址；非 HF 地址原样返回。
     *
     * @param url 原始地址
     * @return 镜像地址
     */
    private static String mirrorOf(String url) {
        return url.startsWith(HF) ? HF_MIRROR + url.substring(HF.length()) : url;
    }

    /**
     * 取 URL 最后一段作为文件名。
     *
     * @param url 地址
     * @return 文件名
     */
    private static String lastSegment(String url) {
        int index = url.lastIndexOf('/');
        return index >= 0 ? url.substring(index + 1) : url;
    }

    /**
     * 判断指定模型目录是否可直接使用（供运维自检）。
     *
     * @param modelId 模型标识
     * @return true 表示 {@code -Dsherpa.onnx.model.dir} 下的同名目录结构完整
     */
    public static boolean isLocallyAvailable(String modelId) {
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        if (root == null || root.isBlank() || modelId == null) {
            return false;
        }
        Path dir = Path.of(root).resolve(modelId);
        return Files.isDirectory(dir) && SherpaModelResolver.isUsableModelDir(dir);
    }
}
