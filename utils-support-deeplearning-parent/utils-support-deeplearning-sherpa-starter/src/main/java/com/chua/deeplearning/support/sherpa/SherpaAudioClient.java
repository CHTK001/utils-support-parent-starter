package com.chua.deeplearning.support.sherpa;

import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.capability.ModelCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 sherpa-onnx 官方 JNI 的本地语音识别客户端。
 *
 * <p>与 {@code utils-support-deeplearning-onnx-starter} 中「纯 Java 复刻 sherpa-onnx 推理流程」
 * 的实现不同，本客户端<b>直接调用 sherpa-onnx 的 C++ 实现</b>：模型前处理、解码、
 * 时间戳与 SenseVoice 的语种/情感/事件标签全部由原生层给出，因此新增模型族只需
 * 换模型目录，不需要再写一份 Java 推理代码。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * VirtualClient asr = VirtualClient.create("sherpa", "");
 * String text = asr.model("/models/sensevoice").transcribe(Path.of("a.wav"));
 *
 * // 也可指定系统属性 sherpa.onnx.model.dir 后直接用模型名
 * // -Dsherpa.onnx.model.dir=/models
 * String text2 = VirtualClient.create("sherpa", "").model("paraformer-zh").transcribe(wav);
 * }</pre>
 *
 * <p>模型族的确定顺序：先看 {@link #model(String)} 是否带 {@code 类型:路径} 前缀
 * （如 {@code sensevoice:/models/sensevoice}），再用 {@link AudioClientSetting#getBaseUrl()}
 * 作为类型提示，最后由 {@link SherpaModelResolver#detect} 按目录内文件命名自动判定。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"sherpa", "sherpa-onnx", "sherpa-asr"})
public class SherpaAudioClient implements VirtualClient {

    /**
     * 模型类型与路径的分隔符。
     */
    private static final char TYPE_SEPARATOR = ':';

    /**
     * 默认模型标识（由系统属性 {@code sherpa.onnx.model.dir} 定位）。
     */
    private static final String DEFAULT_MODEL = "sensevoice";

    /**
     * 客户端配置。
     */
    private final AudioClientSetting setting;

    /**
     * 当前模型标识或目录。
     */
    private String model;

    /**
     * 语言提示。
     */
    private String language;

    /**
     * 采样率覆盖。
     */
    private Integer overrideSampleRate;

    /**
     * 音频字节。
     */
    private byte[] audio;

    /**
     * 音频文件路径。
     */
    private Path audioPath;

    /**
     * 音频输入流。
     */
    private InputStream audioInput;

    /**
     * 懒加载的离线识别器。
     */
    private SherpaRecognizer recognizer;

    /**
     * 懒加载的流式识别器。
     */
    private SherpaStreamingRecognizer streamingRecognizer;

    /**
     * 流式模式标记。
     */
    private boolean streaming;

    /**
     * 构造客户端。
     *
     * @param setting 客户端配置
     */
    public SherpaAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting != null && setting.getModel() != null ? setting.getModel() : null;
        this.language = setting != null ? setting.getLanguage() : null;
        if (setting != null) {
            this.audio = setting.getAudio();
            this.audioPath = setting.getAudioPath();
            this.audioInput = setting.getAudioInput();
            this.overrideSampleRate = setting.getSampleRate();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient model(String model) {
        this.model = model;
        releaseRecognizer();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient language(String language) {
        this.language = language;
        releaseRecognizer();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient sampleRate(Integer sampleRate) {
        this.overrideSampleRate = sampleRate;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient prompt(String prompt) {
        // sherpa-onnx 支持热词文件与规则 FST，但不接受自由文本 prompt；此处仅记录日志便于排障
        if (prompt != null && !prompt.isBlank()) {
            log.debug("[sherpa] prompt 参数对 sherpa-onnx 不生效（如需热词请配置 hotwords 文件）: {}", prompt);
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient audio(byte[] audio) {
        this.audio = audio;
        this.audioPath = null;
        this.audioInput = null;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient audio(InputStream input) {
        this.audioInput = input;
        this.audioPath = null;
        this.audio = null;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VirtualClient audio(Path path) {
        this.audioPath = path;
        this.audio = null;
        this.audioInput = null;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String transcribe(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        SherpaRecognitionResult result = recognizeDetail();
        return result == null ? null : result.getText();
    }

    /**
     * 执行识别并返回完整结果（含分段、时间戳、语种、情感、事件）。
     *
     * @return 识别结果（{@link #streaming()} 模式下由流式链路汇总）
     */
    public SherpaRecognitionResult recognizeDetail() {
        if (streaming) {
            return recognizeStreamingDetail();
        }
        SherpaRecognizer engine = ensureRecognizer();
        if (audioPath != null) {
            return engine.recognize(audioPath);
        }
        if (audio != null) {
            return engine.recognize(audio);
        }
        if (audioInput != null) {
            try {
                return engine.recognize(audioInput.readAllBytes());
            } catch (Exception e) {
                throw new SherpaException("读取音频输入流失败", e);
            }
        }
        throw new SherpaException("未配置音频输入（先调用 audio(path|bytes|stream)）");
    }

    /**
     * 以流式链路转写已配置的音频（一次性喂完再取最终文本）。
     *
     * <p>{@code recognizeDetail()} / {@code transcribe()} 在 {@link #streaming()} 模式下走这里：
     * 流式模型无法用离线识别器解码，必须由 {@link SherpaStreamingRecognizer} 处理。</p>
     *
     * @return 由最终文本汇总出的结果对象（无分段信息）
     */
    private SherpaRecognitionResult recognizeStreamingDetail() {
        byte[] data;
        try {
            if (audioPath != null) {
                data = Files.readAllBytes(audioPath);
            } else if (audio != null) {
                data = audio;
            } else if (audioInput != null) {
                data = audioInput.readAllBytes();
            } else {
                throw new SherpaException("未配置音频输入（先调用 audio(path|bytes|stream)）");
            }
        } catch (SherpaException e) {
            throw e;
        } catch (Exception e) {
            throw new SherpaException("读取音频失败", e);
        }
        float[] samples = SherpaWavUtils.readMono(data, resolveSampleRate());
        long started = System.currentTimeMillis();
        SherpaStreamingRecognizer engine = ensureStreamingRecognizer();
        int chunk = Math.max(1, resolveSampleRate() / 10);
        for (int offset = 0; offset < samples.length; offset += chunk) {
            int length = Math.min(chunk, samples.length - offset);
            float[] slice = new float[length];
            System.arraycopy(samples, offset, slice, 0, length);
            engine.accept(slice);
        }
        String text = engine.finish();
        return SherpaRecognitionResult.builder()
                .text(text)
                .segments(List.of(SherpaRecognitionResult.Segment.builder()
                        .index(0)
                        .start(0D)
                        .end(SherpaWavUtils.durationSeconds(samples.length, resolveSampleRate()))
                        .text(text)
                        .build()))
                .duration(SherpaWavUtils.durationSeconds(samples.length, resolveSampleRate()))
                .elapsedMillis(System.currentTimeMillis() - started)
                .build();
    }

    /**
     * 确保离线识别器已就绪。
     *
     * @return 离线识别器
     */
    private SherpaRecognizer ensureRecognizer() {
        if (recognizer == null) {
            synchronized (this) {
                if (recognizer == null) {
                    String modelRef = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
                    String typeHint = resolveTypeHint();
                    String pathPart = stripTypePrefix(modelRef);
                    recognizer = SherpaRecognizer.builder()
                            .model(pathPart)
                            .modelType(typeHint)
                            .language(language)
                            .numThreads(0)
                            .sampleRate(resolveSampleRate())
                            .build();
                }
            }
        }
        return recognizer;
    }

    /**
     * 解析模型类型提示：优先 {@code 类型:路径} 前缀，其次 {@link AudioClientSetting#getBaseUrl()}。
     *
     * @return 类型短标识，可为 {@code null}
     */
    private String resolveTypeHint() {
        String modelRef = model == null ? "" : model;
        int index = modelRef.indexOf(TYPE_SEPARATOR);
        // Windows 盘符形如 C:\... 不算类型前缀
        if (index > 0 && !(index == 1 && Character.isLetter(modelRef.charAt(0)))) {
            String prefix = modelRef.substring(0, index);
            if (SherpaModelType.of(prefix) != null) {
                return prefix;
            }
        }
        String baseUrl = setting == null ? null : setting.getBaseUrl();
        return baseUrl != null && SherpaModelType.of(baseUrl) != null ? baseUrl : null;
    }

    /**
     * 去掉 {@code 类型:路径} 中的类型前缀。
     *
     * @param modelRef 模型引用
     * @return 纯路径
     */
    private String stripTypePrefix(String modelRef) {
        if (modelRef == null) {
            return null;
        }
        int index = modelRef.indexOf(TYPE_SEPARATOR);
        if (index > 0 && !(index == 1 && Character.isLetter(modelRef.charAt(0)))) {
            String prefix = modelRef.substring(0, index);
            if (SherpaModelType.of(prefix) != null) {
                return modelRef.substring(index + 1).trim();
            }
        }
        return modelRef;
    }

    /**
     * 解析实际采样率：显式覆盖优先，其次 sherpa-onnx 统一 16k。
     *
     * @return 采样率
     */
    private int resolveSampleRate() {
        if (overrideSampleRate != null && overrideSampleRate > 0) {
            return overrideSampleRate;
        }
        return SherpaWavUtils.DEFAULT_SAMPLE_RATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String createTask(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        return "sherpa-" + UUID.randomUUID();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AudioResponse queryTask(String taskId) {
        try {
            SherpaRecognitionResult result = recognizeDetail();
            List<AudioResponse.Segment> segments = new ArrayList<>();
            if (result.getSegments() != null) {
                for (SherpaRecognitionResult.Segment segment : result.getSegments()) {
                    segments.add(AudioResponse.Segment.builder()
                            .id(segment.getIndex())
                            .start(segment.getStart())
                            .end(segment.getEnd())
                            .text(segment.getText())
                            .build());
                }
            }
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(result.getText())
                    .segments(segments)
                    .detectedLanguage(result.getLanguage())
                    .emotion(result.getEmotion())
                    .events(result.getEvents())
                    .build();
        } catch (RuntimeException e) {
            log.error("[sherpa] 识别失败: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 开启流式识别模式（后续 {@link #feedAudio}/{@link #getResult}/{@link #complete} 生效）。
     *
     * @return 当前客户端
     */
    public VirtualClient streaming() {
        this.streaming = true;
        return this;
    }

    /**
     * 是否已开启流式识别模式。
     *
     * @return true 表示 {@link #feedAudio}/{@link #getResult}/{@link #complete} 走流式链路
     */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void feedAudio(float[] samples) {
        // 一旦进入增量喂入，后续 getResult/complete 必须走流式链路
        this.streaming = true;
        SherpaStreamingRecognizer engine = ensureStreamingRecognizer();
        engine.accept(samples);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getResult() {
        if (streamingRecognizer == null) {
            return null;
        }
        return streamingRecognizer.partialText();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String complete() {
        if (streamingRecognizer == null) {
            return transcribe();
        }
        return streamingRecognizer.finish();
    }

    /**
     * 当前流式会话是否检测到一句话结束。
     *
     * @return true 表示端点命中
     */
    public boolean isEndpoint() {
        return streamingRecognizer != null && streamingRecognizer.isEndpoint();
    }

    /**
     * 重置流式会话，开始识别下一句。
     */
    public void resetStreaming() {
        if (streamingRecognizer != null) {
            streamingRecognizer.reset();
        }
    }

    /**
     * 确保流式识别器已就绪。
     *
     * @return 流式识别器
     */
    private SherpaStreamingRecognizer ensureStreamingRecognizer() {
        if (streamingRecognizer == null) {
            synchronized (this) {
                if (streamingRecognizer == null) {
                    String modelRef = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
                    streamingRecognizer = SherpaStreamingRecognizer.builder()
                            .model(stripTypePrefix(modelRef))
                            .language(language)
                            .sampleRate(resolveSampleRate())
                            .build();
                }
            }
        }
        return streamingRecognizer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        releaseRecognizer();
    }

    /**
     * 释放已创建的识别器。
     */
    private synchronized void releaseRecognizer() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (streamingRecognizer != null) {
            streamingRecognizer.close();
            streamingRecognizer = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ModelDefinition> models() {
        List<ModelDefinition> result = new ArrayList<>();
        String root = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        if (root == null || root.isBlank()) {
            return result;
        }
        Path rootDir = Path.of(root);
        if (!Files.isDirectory(rootDir)) {
            return result;
        }
        try (var stream = Files.list(rootDir)) {
            stream.filter(Files::isDirectory)
                    .filter(SherpaModelResolver::isUsableModelDir)
                    .sorted()
                    .forEach(dir -> {
                        String name = dir.getFileName().toString();
                        // 按目录名标注「离线 / 实时」：这两类模型不可互换，
                        // 若界面只给模型名，使用者极易把流式模型选给离线识别器。
                        SherpaUsage usage = SherpaUsage.infer(name);
                        if (usage == SherpaUsage.TTS) {
                            // 合成模型不属于本客户端（识别）的能力范围
                            return;
                        }
                        result.add(ModelDefinition.builder()
                                .id(name)
                                .name(name)
                                .provider("sherpa")
                                .description("sherpa-onnx " + usage.label() + "识别模型（" + usage.description()
                                        + "）: " + dir)
                                .capabilities(List.of(ModelCapabilities.ASR))
                                .build());
                    });
        } catch (Exception e) {
            log.debug("[sherpa] 枚举模型目录失败: {}", e.getMessage());
        }
        return result;
    }
}
