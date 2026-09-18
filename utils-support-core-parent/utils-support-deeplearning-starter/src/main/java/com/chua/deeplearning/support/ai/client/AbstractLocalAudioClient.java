package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
* 本地引擎语音识别（ASR）客户端抽象基类。
* <p>
* 统一实现 {@link VirtualClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
* 已注册的 byte[]→字符串 翻译器执行语音转写，并提供该引擎的模型列表。
* 子类只需指定引擎名称（如 "onnx"）。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractLocalAudioClient implements VirtualClient {

    /**
    * 引擎名称（提供者）
    */
    protected final String engine;

    /**
    * 识别引擎实例
    */
    protected final IdentificationEngine identificationEngine;

    /**
    * 当前模型名称
    */
    protected String model;

    /**
    * 识别语言
    */
    protected String language;

    /**
    * 音频路径
    */
    protected Path audioPath;

    /**
    * 音频字节
    */
    protected byte[] audio;

    /**
    * 构造本地 ASR 客户端。
    *
    * @param engine  引擎名称，如 "onnx"
    * @param setting 客户端配置
    */
    protected AbstractLocalAudioClient(String engine, AudioClientSetting setting) {
        this.engine = engine;
        this.identificationEngine = AbstractIdentificationEngine.getInstance();
        this.model = setting != null ? setting.getModel() : null;
        this.language = setting != null ? setting.getLanguage() : null;
    }

    @Override
    /** 模型 */
    public VirtualClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** Language */
    public VirtualClient language(String language) {
        this.language = language;
        return this;
    }

    @Override
    /** 音频 */
    public VirtualClient audio(Path path) {
        this.audioPath = path;
        this.audio = null;
        return this;
    }

    @Override
    /** 音频 */
    public VirtualClient audio(byte[] audio) {
        this.audio = audio;
        this.audioPath = null;
        return this;
    }

    @Override
    /** 音频 */
    public VirtualClient audio(InputStream input) {
        try {
            this.audio = input != null ? input.readAllBytes() : null;
            this.audioPath = null;
        } catch (Exception e) {
            throw new RuntimeException("读取音频输入流失败", e);
        }
        return this;
    }

    /**
    * 解析实际使用的模型名称。
    *
    * <p>{@code auto} / 空值表示按当前服务器硬件配置自动挑选推荐模型，
    * 否则返回显式指定的模型名。</p>
    *
    * @return 模型名称
    */
    protected String resolveModel() {
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            return model;
        }
        String recommended = DeeplearningModels.recommended(engine, null);
        if (recommended != null) {
            return recommended;
        }
        List<ModelDefinition> defs = models();
        if (defs.isEmpty()) {
            throw new IllegalStateException("引擎[" + engine + "]没有可用的 ASR 模型");
        }
        return defs.getFirst().getId();
    }

    @Override
    /** Transcribe */
    public String transcribe(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        try {
            byte[] data = resolveAudio();
            String modelName = resolveModel();
            @SuppressWarnings("unchecked")
            ITranslator<Object, Object> translator =
                    (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
            if (translator == null) {
                throw new IllegalStateException("模型未注册: " + modelName);
            }
            Object result = translator.translate(data);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            throw new RuntimeException("本地 ASR 转写失败", e);
        }
    }

    /**
    * 解析音频字节数据。
    *
    * @return 音频字节
    * @throws Exception 读取失败
    */
    private byte[] resolveAudio() throws Exception {
        if (audio != null) {
            return audio;
        }
        if (audioPath != null) {
            return Files.readAllBytes(audioPath);
        }
        throw new IllegalStateException("未配置音频输入 (调用 audio(path|bytes|stream) 先)");
    }

    @Override
    /** 创建任务 */
    public String createTask(Path path) {
        return "asr-" + UUID.randomUUID();
    }

    @Override
    /** 查询任务 */
    public AudioResponse queryTask(String taskId) {
        try {
            String transcript = transcribe(audioPath);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(transcript)
                    .build();
        } catch (Exception e) {
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}

