package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
* 说话人分离（Speaker Diarization）接口，将多说话人音频按说话人归属切分为带时间戳的片段。
*
* <h2>什么是说话人分离</h2>
* <p>说话人分离（Diarization，源自希腊语 diárisis = "分节"）是语音处理的核心任务之一，
* 目标是回答一个问题：<b>"谁在什么时候说了话？"</b></p>
* <p>它与说话人识别（Speaker Identification，即"这是谁"）的区别：
* <ul>
*   <li><b>识别（Identification）</b>：给定一段语音，判断说话人身份（1:1 比对或 1:N 检索）。</li>
*   <li><b>分离（Diarization）</b>：给定一段多说话人音频，自动将各时间段分配说话人标签，
*       无需预先知道有哪些人。</li>
* </ul>
* </p>
*
* <h2>典型应用场景</h2>
* <ul>
*   <li><b>会议录音分析</b>：自动区分每位参会者的发言时段，生成发言时间线。</li>
*   <li><b>电话客服质检</b>：分离客户与客服两个声道的发言内容。</li>
*   <li><b>监控音频分析</b>：识别多人对话场景，定位关键发言时段。</li>
*   <li><b>播客/访谈剪辑</b>：自动按说话人切分音频，便于后期编辑。</li>
* </ul>
*
* <h2>输出数据结构</h2>
* <p>每个片段由 {@link SpeakerSegment} 描述，包含说话人 ID、起止时间和可选转录文本。
* 返回的列表按时间升序排列，同一说话人的多个片段可能持有相同 标识。</p>
*
* <h2>与 ASR 的配合</h2>
* <p>说话人分离仅负责<b>时间切分</b>和<b>说话人归属</b>，不进行文字转录。
* 实际工作流通常是：</p>
* <pre>{@code
*   // 第一步：说话人分离
*   List<SpeakerSegment> segments = SpeakerDiarizer.create("onnx")
*       .model("pyannote-diarization").diarize(audioBytes);
*
*   // 第二步：对每个片段分别做 ASR 转写
*   for (SpeakerSegment seg : segments) {
*       String text = VirtualClient.create("whisper", "")
*           .model("whisper-tiny")
*           .transcribe(cropAudio(audioBytes, seg.startTimeMs(), seg.durationMs()));
*       // 将文本回填到片段中
*   }
* }</pre>rtTimeMs(), seg.durationMs()));
*       // 将文本回填到片段中
*   }
* }</pre>
*
* <h2>嵌入式模型推荐</h2>
* <ul>
*   <li><b>能量 VAD 方案</b>（本实现默认）：无深度学习依赖，速度快，适合低资源环境。</li>
*   <li><b>pyannote.audio</b>：工业级说话人分离，精度最高，但模型较大（~1.5GB）。</li>
*   <li><b>Wespeaker + VAD</b>：轻量级 x-vector 说话人嵌入 + 能量 VAD 分段，平衡精度与体积。</li>
* </ul>
*
* @author CH
* @since 4.0.0.43
 */
public interface SpeakerDiarizer {

    /**
    * 通过 SPI 创建说话人分离实例。
    *
    * @param provider 引擎提供商，如 "onnx"
    * @param apiKey   API 密钥；本地引擎可传入空字符串
    * @return 新建实例
    */
    static SpeakerDiarizer create(String provider, String apiKey) {
        return ServiceProvider.of(SpeakerDiarizer.class)
                .getNewExtension(provider, apiKey);
    }

    /**
    * 设置 SPI 提供者（链式调用）。
    */
    default SpeakerDiarizer provider(String provider) {
        return this;
    }

    /**
    * 设置模型 标识（链式调用）。
    *
    * @param model 模型标识，须在 模型registry 中以 speakerdiarizer.类 注册
    * @return this
    */
    default SpeakerDiarizer model(String model) {
        return this;
    }

    /**
    * 以默认配置创建实例。
    *
    * @param name 模型名称
    * @return 说话人分离实例
    */
    static SpeakerDiarizer create(String name) {
        return new DefaultSpeakerDiarizer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
    * 查询当前引擎下所有已注册的说话人分离模型 标识 列表。
    *
    * @return 模型 标识 列表
    */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.audio.SpeakerDiarizer.class);
    }

    /**
    * 以自定义配置创建实例。
    *
    * @param name    模型名称
    * @param setting 模型配置
    * @return 说话人分离实例
    */
    static SpeakerDiarizer create(String name, ModelSetting setting) {
        return new DefaultSpeakerDiarizer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
    * 设置模型本地路径（绝对路径或 类路径 路径）。
    *
    * @param path 模型路径
    * @return this
    */
    SpeakerDiarizer modelPath(String path);

    /**
    * 设置推理设备（"cpu" 或 "cuda"）。
    *
    * @param device 设备标识
    * @return this
    */
    SpeakerDiarizer device(String device);

    /**
    * 设置音频采样率（Hz）。
    *
    * <p>说话人分离模型通常要求 16kHz 输入。
    * 若原始音频采样率不同，内部将自动重采样。</p>
    *
    * @param sampleRate 采样率，默认 16000
    * @return this
    */
    SpeakerDiarizer sampleRate(int sampleRate);

    /**
    * 设置最大说话人数上限。
    *
    * <p>部分模型支持在推理前指定期望的说话人数量，可提升小样本场景的准确率。
    * 传入 {@code null} 或不设置表示不做限制。</p>
    *
    * @param maxSpeakers 最大说话人数，0 表示不限制
    * @return this
    */
    SpeakerDiarizer maxSpeakers(int maxSpeakers);

    /**
    * 对音频字节数据进行说话人分离。
    *
    * <p>输入音频应为 16kHz 单声道 PCM/WAV 格式。
    * 若为其他格式，内部将尝试自动解码和重采样。</p>
    *
    * @param audioData 音频原始字节
    * @return 按时间排序的说话人片段列表，每个片段含说话人 标识 和时间戳
    */
    List<SpeakerSegment> diarize(byte[] audioData);

    /**
    * 对音频文件进行说话人分离。
    *
    * @param path 音频文件路径
    * @return 按时间排序的说话人片段列表
    */
    List<SpeakerSegment> diarize(java.nio.file.Path path);
}

