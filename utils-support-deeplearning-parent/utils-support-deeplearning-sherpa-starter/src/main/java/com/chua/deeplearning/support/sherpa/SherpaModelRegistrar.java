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
 * <h3>离线模型 vs 实时（流式）模型</h3>
 *
 * <p>sherpa-onnx 的模型在<b>导出阶段</b>就分成了两类，<b>不可互换</b>。登记时统一用
 * {@link SherpaUsage} 标注，模型标识也带上前缀，避免选错：</p>
 *
 * <table border="1">
 *   <caption>模型选择对照</caption>
 *   <tr>
 *     <th>场景</th><th>用哪个模型</th><th>模型标识前缀</th><th>驱动类</th>
 *   </tr>
 *   <tr>
 *     <td>录音文件转写（要准确率）</td>
 *     <td>Paraformer / SenseVoice / Whisper / 离线 Zipformer</td>
 *     <td>{@code sherpa-offline-*}</td>
 *     <td>{@link SherpaAsrPipeline}、{@link SherpaRecognizer}</td>
 *   </tr>
 *   <tr>
 *     <td>麦克风实时识别（要低延迟）</td>
 *     <td>流式 Zipformer（名字里带 {@code streaming}）</td>
 *     <td>{@code sherpa-streaming-*}</td>
 *     <td>{@link SherpaStreamingRecognizer}</td>
 *   </tr>
 *   <tr>
 *     <td>语音合成</td>
 *     <td>VITS / Matcha / Kokoro</td>
 *     <td>{@code sherpa-tts-*}</td>
 *     <td>{@link SherpaSynthesizer}、{@link SherpaTextToAudioClient}</td>
 *   </tr>
 * </table>
 *
 * <p><b>判别口诀</b>：目录／仓库名里带 {@code streaming} 就是实时模型，否则是离线模型；
 * 两类文件名都可能是 {@code encoder/decoder/joiner.int8.onnx}，<b>不能只看文件名</b>。
 * 把流式模型交给离线识别器会解码失败或输出乱码（{@link SherpaRecognizer} 已做拦截提示）。</p>
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
     *
     * <p>按 {@link SherpaUsage} 分三段登记，便于一眼看出「离线用哪个、实时用哪个」。</p>
     */
    private static void registerAll() {
        // ==================== 一、离线识别（offline）：录音文件转写，准确率优先 ====================
        reg(SherpaUsage.OFFLINE, "sherpa-offline-sensevoice",
                HF + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                List.of("model.int8.onnx", "tokens.txt"),
                "多语言（中/英/日/韩/粤），自带语种、情感与音频事件标签；约 228MB");

        reg(SherpaUsage.OFFLINE, "sherpa-offline-paraformer-zh",
                HF + "sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main/model.int8.onnx",
                List.of("model.int8.onnx", "tokens.txt"),
                "纯中文，非自回归单次前向，速度快；适合短语音批量转写");

        reg(SherpaUsage.OFFLINE, "sherpa-offline-whisper-tiny",
                HF + "sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                List.of("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt"),
                "Whisper tiny 多语言，编码器/解码器双模型；资源占用低");

        // ==================== 二、实时/流式识别（streaming）：麦克风边说边出字 ====================
        reg(SherpaUsage.STREAMING, "sherpa-streaming-zipformer-zh-en",
                HF + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
                List.of("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
                "中英双语 Zipformer Transducer，chunk 增量解码；约 193MB");

        reg(SherpaUsage.STREAMING, "sherpa-streaming-zipformer-zh-14M",
                HF + "sherpa-onnx-streaming-zipformer-zh-14M/resolve/main/encoder-epoch-99-avg-1.int8.onnx",
                List.of("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
                "纯中文超轻量流式模型（14M 参数），适合端侧实时识别；约 31MB");

        // ==================== 三、语音合成（tts）：文本转音频 ====================
        reg(SherpaUsage.TTS, "sherpa-tts-vits-zh",
                HF + "sherpa-onnx-vits-zh-ll/resolve/main/model.onnx",
                List.of("model.onnx", "tokens.txt", "lexicon.txt", "speakers.txt"),
                "中文 VITS，多说话人；8kHz 输出");
    }

    /**
     * 登记一个模型元数据。
     *
     * <p>模型标识统一加 {@link SherpaUsage#code()} 前缀，能力接口按使用形态区分：
     * 离线/流式都用 {@link VirtualClient}（语音识别），合成用 {@link TextToAudioClient}。</p>
     *
     * @param usage    使用形态（离线 / 实时 / 合成）
     * @param modelId  模型标识（需自带使用形态前缀）
     * @param mainUrl  主文件下载地址
     * @param files    附属文件清单（相对主文件同目录）
     * @param describe 描述
     */
    private static void reg(SherpaUsage usage, String modelId, String mainUrl,
                            List<String> files, String describe) {
        if (ModelRegistry.get(modelId) != null) {
            return;
        }
        String fileName = lastSegment(mainUrl);
        boolean synthesis = usage == SherpaUsage.TTS;
        ModelRegistry.register(modelId, null,
                synthesis ? String.class : byte[].class,
                synthesis ? byte[].class : String.class,
                synthesis ? TextToAudioClient.class : VirtualClient.class,
                modelId + "/" + fileName, mainUrl, List.of(mirrorOf(mainUrl)), files, false, fileName);
        // 日志带上使用形态，便于排查「选错离线/实时模型」类问题
        log.info("[sherpa] 登记[{}]模型 {}（{}）: {}", usage.label(), modelId, usage.description(), describe);
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

    /**
     * 按使用形态列出模型标识（供控制台按「离线/实时/合成」分组展示）。
     *
     * @param usage 使用形态
     * @return 该形态下的模型标识列表（按 {@link SherpaUsage#code()} 前缀匹配）
     */
    public static List<String> modelIdsOf(SherpaUsage usage) {
        if (usage == null) {
            return List.of();
        }
        return ModelRegistry.getAll().stream()
                .map(ModelRegistry.Entry::modelId)
                .filter(id -> id != null && id.startsWith("sherpa-" + usage.code()))
                .sorted()
                .toList();
    }
}
